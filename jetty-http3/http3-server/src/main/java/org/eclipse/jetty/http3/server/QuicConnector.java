package org.eclipse.jetty.http3.server;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http3.quic.QuicheConfig;
import org.eclipse.jetty.http3.quic.QuicheConnection;
import org.eclipse.jetty.http3.quic.QuicheConnectionId;
import org.eclipse.jetty.http3.quic.quiche.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnector extends AbstractNetworkConnector
{
    protected static final Logger LOG = LoggerFactory.getLogger(QuicConnector.class);

    private final Map<QuicheConnectionId, QuicConnection> connections = new ConcurrentHashMap<>();
    private Selector selector;
    private DatagramChannel channel;
    private QuicheConfig quicheConfig;
    private CommandManager commandManager;
    private SSLKeyPair keyPair;
    private SelectionKey selectionKey;

    public QuicConnector(Server server)
    {
        super(server, null, null, null, 0);
    }

    public SSLKeyPair getKeyPair()
    {
        return keyPair;
    }

    public void setKeyPair(SSLKeyPair keyPair)
    {
        this.keyPair = keyPair;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        commandManager = new CommandManager(getByteBufferPool());
        getScheduler().schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);
        getExecutor().execute(this::accept);
    }

    @Override
    public void open() throws IOException
    {
        if (selector != null)
            return;

        if (keyPair == null)
            throw new IllegalStateException("Missing key pair");

        File[] files;
        try
        {
            files = keyPair.export(new File(System.getProperty("java.io.tmpdir")));
        }
        catch (Exception e)
        {
            throw new IOException("Error exporting key pair", e);
        }

        quicheConfig = new QuicheConfig();
        quicheConfig.setPrivKeyPemPath(files[0].getPath());
        quicheConfig.setCertChainPemPath(files[1].getPath());
        quicheConfig.setVerifyPeer(false);
        quicheConfig.setMaxIdleTimeout(5000L);
        quicheConfig.setInitialMaxData(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiRemote(10000000L);
        quicheConfig.setInitialMaxStreamDataUni(10000000L);
        quicheConfig.setInitialMaxStreamsBidi(100L);
        quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.RENO);
//        quicConfig.setApplicationProtos(getProtocols().toArray(new String[0]));
        quicheConfig.setApplicationProtos("http/0.9");  // enable HTTP/0.9

        this.selector = Selector.open();
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        this.selectionKey = this.channel.register(selector, SelectionKey.OP_READ);
        this.channel.bind(bindAddress());
    }

    @Override
    public void close()
    {
        if (selector == null)
            return;

        connections.values().forEach(QuicConnection::dispose);
        connections.clear();
        selectionKey.cancel();
        selectionKey = null;
        IO.close(channel);
        channel = null;
        IO.close(selector);
        selector = null;
        quicheConfig = null;
        commandManager = null;
    }

    public QuicStreamEndPoint createQuicStreamEndPoint(QuicConnection quicConnection, long streamId)
    {
        QuicStreamEndPoint endPoint = new QuicStreamEndPoint(getScheduler(), quicConnection, streamId);
        Connection connection = getDefaultConnectionFactory().newConnection(this, endPoint);
        endPoint.setConnection(connection);
        connection.onOpen();
        return endPoint;
    }

    private void fireTimeoutNotificationIfNeeded()
    {
        boolean timedOut = connections.values().stream().map(QuicConnection::hasQuicConnectionTimedOut).findFirst().orElse(false);
        if (timedOut)
        {
            LOG.debug("connection timed out, waking up selector");
            selector.wakeup();
        }
        getScheduler().schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);
    }

    private void accept()
    {
        String oldName = Thread.currentThread().getName();
        Thread.currentThread().setName("jetty-quic-acceptor");
        while (true)
        {
            try
            {
                selectOnce();
            }
            catch (IOException e)
            {
                LOG.error("error during selection", e);
            }
            catch (InterruptedException e)
            {
                LOG.debug("interruption during selection", e);
                break;
            }
        }
        Thread.currentThread().setName(oldName);
    }

    private void selectOnce() throws IOException, InterruptedException
    {
        int selected = selector.select();
        if (Thread.interrupted())
            throw new InterruptedException("Selector thread was interrupted");
        if (isStopping() || isStopped())
            throw new InterruptedException("Server is " + getState());

        if (selected == 0)
        {
            LOG.debug("no selected key; a QUIC connection has timed out");
            boolean needWrite = processTimeout();
            int ops = SelectionKey.OP_READ | (needWrite ? SelectionKey.OP_WRITE : 0);
            LOG.debug("setting key interest to " + ops);
            selectionKey.interestOps(ops);
            return;
        }

        Iterator<SelectionKey> selectorIt = selector.selectedKeys().iterator();
        while (selectorIt.hasNext())
        {
            SelectionKey key = selectorIt.next();
            selectorIt.remove();
            LOG.debug("Processing selected key {}", key);
            boolean needWrite = false;

            if (key.isReadable())
            {
                needWrite |= processReadableKey();
            }

            if (key.isWritable())
            {
                needWrite |= processWritableKey();
            }

            int ops = SelectionKey.OP_READ | (needWrite ? SelectionKey.OP_WRITE : 0);
            LOG.debug("setting key interest to " + ops);
            key.interestOps(ops);
        }
    }

    private boolean processTimeout() throws IOException
    {
        boolean needWrite = false;
        Iterator<QuicConnection> it = connections.values().iterator();
        while (it.hasNext())
        {
            QuicConnection quicConnection = it.next();
            if (quicConnection.hasQuicConnectionTimedOut())
            {
                LOG.debug("connection has timed out: " + quicConnection);
                boolean closed = quicConnection.isQuicConnectionClosed();
                if (closed)
                {
                    quicConnection.markClosed();
                    it.remove();
                    LOG.debug("connection closed due to timeout; remaining connections: " + connections);
                }
                needWrite = commandManager.quicTimeout(quicConnection, channel, closed);
            }
        }
        return needWrite;
    }

    private boolean processReadableKey() throws IOException
    {
        ByteBufferPool bufferPool = getByteBufferPool();

        ByteBuffer buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
        BufferUtil.flipToFill(buffer);
        SocketAddress peer = channel.receive(buffer);
        buffer.flip();

        QuicheConnectionId connectionId = QuicheConnectionId.fromPacket(buffer);
        QuicConnection connection = connections.get(connectionId);
        boolean needWrite;
        if (connection == null)
        {
            LOG.debug("got packet for a new connection");
            // new connection
            QuicheConnection acceptedQuicheConnection = QuicheConnection.tryAccept(quicheConfig, peer, buffer);
            if (acceptedQuicheConnection == null)
            {
                LOG.debug("new connection negotiation");
                ByteBuffer negociationBuffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
                BufferUtil.flipToFill(negociationBuffer);
                if (QuicheConnection.negociate(peer, buffer, negociationBuffer))
                {
                    bufferPool.release(buffer);
                    needWrite = commandManager.channelWrite(channel, negociationBuffer, peer);
                }
                else
                {
                    bufferPool.release(buffer);
                    bufferPool.release(negociationBuffer);
                    needWrite = false;
                }
            }
            else
            {
                LOG.debug("new connection accepted");
                bufferPool.release(buffer);
                connection = new QuicConnection(acceptedQuicheConnection, (InetSocketAddress)channel.getLocalAddress(), (InetSocketAddress)peer, this);
                connections.put(connectionId, connection);
                needWrite = commandManager.quicSend(connection, channel);
            }
        }
        else
        {
            LOG.debug("got packet for an existing connection: " + connectionId + " - buffer: p=" + buffer.position() + " r=" + buffer.remaining());
            // existing connection
            connection.quicRecv(buffer, (InetSocketAddress)peer);
            bufferPool.release(buffer);
            // Bug? quiche apparently does not send the stream frames after the connection has been closed
            // -> use a mark-as-closed mechanism and first send the data then close
            needWrite = commandManager.quicSend(connection, channel);
            if (connection.isMarkedClosed() && connection.closeQuicConnection())
                needWrite |= commandManager.quicSend(connection, channel);
        }
        return needWrite;
    }

    private boolean processWritableKey() throws IOException
    {
        return commandManager.processQueue();
    }

    private SocketAddress bindAddress()
    {
        String host = getHost();
        if (host == null)
            host = "0.0.0.0";
        int port = getPort();
        if (port < 0)
            throw new IllegalArgumentException("port cannot be negative: " + port);
        return new InetSocketAddress(host, port);
    }

    @Override
    public Object getTransport()
    {
        return channel;
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = this.channel;
        return channel != null && channel.isOpen();
    }

    @Override
    protected void accept(int acceptorID)
    {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has its own accepting mechanism");
    }
}
