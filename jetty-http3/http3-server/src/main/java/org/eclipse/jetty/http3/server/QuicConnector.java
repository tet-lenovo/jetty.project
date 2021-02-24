package org.eclipse.jetty.http3.server;

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

import org.eclipse.jetty.http3.quic.QuicConfig;
import org.eclipse.jetty.http3.quic.QuicConnection;
import org.eclipse.jetty.http3.quic.QuicConnectionId;
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

    private Selector selector;
    private DatagramChannel channel;
    private QuicConfig quicConfig;
    private CommandManager commandManager;

    private final Map<QuicConnectionId, QuicEndPointManager> endpointManagers = new ConcurrentHashMap<>();
    private final Runnable selection = () ->
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
    };

    public QuicConnector(Server server)
    {
        super(server, null, null, null, 0);
    }

    @Override
    protected void doStart() throws Exception
    {
        LibQuiche.Logging.enable(); // load the quiche native lib
        super.doStart();
        getScheduler().schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);
        getExecutor().execute(selection);
        commandManager = new CommandManager(getByteBufferPool());
    }

    @Override
    public void open() throws IOException
    {
        if (selector != null)
            return;

        quicConfig = new QuicConfig();
        quicConfig.setMaxIdleTimeout(5000L);
        quicConfig.setInitialMaxData(10000000L);
        quicConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        quicConfig.setInitialMaxStreamDataBidiRemote(10000000L);
        quicConfig.setInitialMaxStreamDataUni(10000000L);
        quicConfig.setInitialMaxStreamsBidi(100L);
        quicConfig.setCongestionControl(QuicConfig.CongestionControl.RENO);
        quicConfig.setCertChainPemPath("./src/test/resources/cert.crt");
        quicConfig.setPrivKeyPemPath("./src/test/resources/cert.key");
        quicConfig.setVerifyPeer(false);
//        quicConfig.setApplicationProtos(getProtocols().toArray(new String[0]));
        quicConfig.setApplicationProtos("http/0.9");  // enable HTTP/0.9

        this.selector = Selector.open();
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        this.channel.register(selector, SelectionKey.OP_READ);
        this.channel.bind(bindAddress());
    }

    @Override
    public void close()
    {
        if (selector == null)
            return;

        endpointManagers.values().forEach(QuicEndPointManager::dispose);
        endpointManagers.clear();
        IO.close(channel);
        channel = null;
        IO.close(selector);
        selector = null;
        quicConfig = null;
        commandManager = null;
    }

    private void fireTimeoutNotificationIfNeeded()
    {
        boolean timedOut = endpointManagers.values().stream().map(QuicEndPointManager::hasQuicConnectionTimedOut).findFirst().orElse(false);
        if (timedOut)
        {
            LOG.debug("connection timed out, waking up selector");
            selector.wakeup();
        }
        getScheduler().schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);
    }

    private void selectOnce() throws IOException, InterruptedException
    {
        int selected = selector.select();
        if (Thread.interrupted())
            throw new InterruptedException("Selector thread was interrupted");

        if (selected == 0)
        {
            LOG.debug("no selected key; a QUIC connection has timed out");
            processTimeout();
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

    private void processTimeout() throws IOException
    {
        boolean needWrite = false;
        Iterator<QuicEndPointManager> it = endpointManagers.values().iterator();
        while (it.hasNext())
        {
            QuicEndPointManager quicEndPointManager = it.next();
            if (quicEndPointManager.hasQuicConnectionTimedOut())
            {
                LOG.debug("connection has timed out: " + quicEndPointManager);
                boolean closed = quicEndPointManager.isQuicConnectionClosed();
                if (closed)
                {
                    quicEndPointManager.markClosed();
                    it.remove();
                    LOG.debug("connection closed due to timeout; remaining connections: " + endpointManagers);
                }
                needWrite = commandManager.quicTimeout(quicEndPointManager, channel, closed);
            }
        }
        //TODO: re-registering might leak some memory, check that
        channel.register(selector, SelectionKey.OP_READ | (needWrite ? SelectionKey.OP_WRITE : 0));
    }

    private boolean processWritableKey() throws IOException
    {
        return commandManager.processQueue();
    }

    private boolean processReadableKey() throws IOException
    {
        ByteBufferPool bufferPool = getByteBufferPool();

        ByteBuffer buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
        BufferUtil.flipToFill(buffer);
        SocketAddress peer = channel.receive(buffer);
        buffer.flip();

        QuicConnectionId connectionId = QuicConnectionId.fromPacket(buffer);
        QuicEndPointManager endPointManager = endpointManagers.get(connectionId);
        boolean needWrite;
        if (endPointManager == null)
        {
            LOG.debug("got packet for a new connection");
            // new connection
            ByteBuffer newConnectionNegotiationToSend = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
            BufferUtil.flipToFill(newConnectionNegotiationToSend);
            QuicConnection acceptedQuicConnection = QuicConnection.tryAccept(quicConfig, peer, buffer, newConnectionNegotiationToSend);
            bufferPool.release(buffer);
            if (acceptedQuicConnection == null)
            {
                LOG.debug("new connection negotiation");
                needWrite = commandManager.channelWrite(newConnectionNegotiationToSend, channel, peer);
            }
            else
            {
                LOG.debug("new connection accepted");
                bufferPool.release(newConnectionNegotiationToSend);
                endPointManager = new QuicEndPointManager(acceptedQuicConnection, (InetSocketAddress)channel.getLocalAddress(), (InetSocketAddress)peer, this);
                endpointManagers.put(connectionId, endPointManager);
                needWrite = commandManager.quicSend(channel, endPointManager);
            }
        }
        else
        {
            LOG.debug("got packet for an existing connection: " + connectionId + " - buffer: p=" + buffer.position() + " r=" + buffer.remaining());
            // existing connection
            endPointManager.handlePacket(buffer, (InetSocketAddress)peer, bufferPool);
            // Bug? quiche apparently does not send the stream frames after the connection has been closed
            // -> use a mark-as-closed mechanism and first send the data then close
            needWrite = commandManager.quicSend(channel, endPointManager);
            if (endPointManager.isMarkedClosed() && endPointManager.closeQuicConnection())
                needWrite |= commandManager.quicSend(channel, endPointManager);
        }
        return needWrite;
    }

    QuicStreamEndPoint createQuicStreamEndPoint(QuicEndPointManager quicEndPointManager, long streamId)
    {
        QuicStreamEndPoint endPoint = new QuicStreamEndPoint(getScheduler(), quicEndPointManager, streamId);
        Connection connection = getDefaultConnectionFactory().newConnection(this, endPoint);
        endPoint.setConnection(connection);
        connection.onOpen();
        return endPoint;
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
