package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import org.eclipse.jetty.http3.quic.QuicConfig;
import org.eclipse.jetty.http3.quic.QuicConnection;
import org.eclipse.jetty.http3.quic.QuicConnectionId;
import org.eclipse.jetty.http3.quic.quiche.LibQuiche;
import org.eclipse.jetty.io.AbstractEndPoint;
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

    private final Map<QuicConnectionId, QuicEndPoint> endpoints = new ConcurrentHashMap<>();
    private final Deque<Command> commands = new ArrayDeque<>();
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
        super.doStart();
        getScheduler().schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);
        getExecutor().execute(selection);
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

        endpoints.values().forEach(AbstractEndPoint::close);
        endpoints.clear();
        IO.close(channel);
        channel = null;
        IO.close(selector);
        selector = null;
        quicConfig = null;
    }

    private void fireTimeoutNotificationIfNeeded()
    {
        boolean timedOut = endpoints.values().stream().map(QuicEndPoint::hasTimedOut).findFirst().orElse(false);
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
        Iterator<QuicEndPoint> it = endpoints.values().iterator();
        while (it.hasNext())
        {
            QuicEndPoint quicEndPoint = it.next();
            if (quicEndPoint.hasTimedOut())
            {
                LOG.debug("connection has timed out: " + quicEndPoint);
                boolean closed = quicEndPoint.getQuicConnection().isConnectionClosed();
                if (closed)
                {
                    quicEndPoint.close();
                    it.remove();
                    LOG.debug("connection closed due to timeout; remaining connections: " + endpoints);
                }
                QuicTimeoutCommand quicTimeoutCommand = new QuicTimeoutCommand(getByteBufferPool(), quicEndPoint, channel, closed);
                if (!quicTimeoutCommand.execute())
                {
                    commands.offer(quicTimeoutCommand);
                    needWrite = true;
                }
            }
        }
        //TODO: re-registering might leak some memory, check that
        channel.register(selector, SelectionKey.OP_READ | (needWrite ? SelectionKey.OP_WRITE : 0));
    }

    private boolean processWritableKey() throws IOException
    {
        boolean needWrite = false;
        LOG.debug("key is writable, commands = " + commands);
        while (!commands.isEmpty())
        {
            Command command = commands.poll();
            LOG.debug("executing command " + command);
            boolean finished = command.execute();
            LOG.debug("executed command; finished? " + finished);
            if (!finished)
            {
                commands.offer(command);
                needWrite = true;
                break;
            }
        }
        return needWrite;
    }

    private boolean processReadableKey() throws IOException
    {
        boolean needWrite = false;
        ByteBufferPool bufferPool = getByteBufferPool();

        ByteBuffer buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
        BufferUtil.flipToFill(buffer);
        SocketAddress peer = channel.receive(buffer);
        buffer.flip();

        QuicConnectionId connectionId = QuicConnectionId.fromPacket(buffer);
        QuicEndPoint endPoint = endpoints.get(connectionId);
        if (endPoint == null)
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
                ChannelWriteCommand channelWriteCommand = new ChannelWriteCommand(bufferPool, newConnectionNegotiationToSend, channel, peer);
                if (!channelWriteCommand.execute())
                {
                    commands.offer(channelWriteCommand);
                    needWrite = true;
                }
            }
            else
            {
                LOG.debug("new connection accepted");
                bufferPool.release(newConnectionNegotiationToSend);
                endPoint = new QuicEndPoint(getScheduler(), acceptedQuicConnection, channel.getLocalAddress(), peer, this);
                endpoints.put(connectionId, endPoint);
                QuicSendCommand quicSendCommand = new QuicSendCommand(bufferPool, channel, endPoint);
                if (!quicSendCommand.execute())
                {
                    commands.offer(quicSendCommand);
                    needWrite = true;
                }
            }
        }
        else
        {
            LOG.debug("got packet for an existing connection: " + connectionId + " - buffer: p=" + buffer.position() + " r=" + buffer.remaining());
            // existing connection
            endPoint.handlePacket(buffer, peer, bufferPool);
            QuicSendCommand quicSendCommand = new QuicSendCommand(bufferPool, channel, endPoint);
            if (!quicSendCommand.execute())
            {
                commands.offer(quicSendCommand);
                needWrite = true;
            }
            if (!endPoint.isOpen() && endPoint.getQuicConnection().sendClose())
            {
                quicSendCommand = new QuicSendCommand(bufferPool, channel, endPoint);
                if (!quicSendCommand.execute())
                {
                    commands.offer(quicSendCommand);
                    needWrite = true;
                }
            }
        }
        return needWrite;
    }

    QuicStreamEndPoint createQuicStreamEndPoint(QuicEndPoint quicEndPoint, long streamId)
    {
        QuicStreamEndPoint endPoint = new QuicStreamEndPoint(getScheduler(), quicEndPoint, streamId);
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



    private interface Command
    {
        boolean execute() throws IOException;
    }

    private static class QuicTimeoutCommand implements Command
    {
        private final QuicSendCommand quicSendCommand;
        private final boolean close;
        private boolean timeoutCalled;

        public QuicTimeoutCommand(ByteBufferPool bufferPool, QuicEndPoint quicEndPoint, DatagramChannel channel, boolean close)
        {
            this.close = close;
            this.quicSendCommand = new QuicSendCommand("timeout", bufferPool, channel, quicEndPoint);
        }

        @Override
        public boolean execute() throws IOException
        {
            if (!timeoutCalled)
            {
                LOG.debug("notifying quiche of timeout");
                quicSendCommand.quicConnection.onTimeout();
                timeoutCalled = true;
            }
            boolean written = quicSendCommand.execute();
            if (!written)
                return false;
            if (close)
            {
                LOG.debug("closing quiche connection");
                quicSendCommand.quicConnection.close();
            }
            return true;
        }
    }

    private static class QuicSendCommand implements Command
    {
        private final String cmdName;
        private final ByteBufferPool bufferPool;
        private final QuicConnection quicConnection;
        private final DatagramChannel channel;
        private final SocketAddress peer;
        private final LongConsumer timeoutConsumer;

        private ByteBuffer buffer;

        public QuicSendCommand(ByteBufferPool bufferPool, DatagramChannel channel, QuicEndPoint endPoint)
        {
            this("send", bufferPool, channel, endPoint);
        }

        private QuicSendCommand(String cmdName, ByteBufferPool bufferPool, DatagramChannel channel, QuicEndPoint endPoint)
        {
            this.cmdName = cmdName;
            this.bufferPool = bufferPool;
            this.quicConnection = endPoint.getQuicConnection();
            this.channel = channel;
            this.peer = endPoint.getLastPeer();
            this.timeoutConsumer = endPoint.getTimeoutSetter();
        }

        @Override
        public boolean execute() throws IOException
        {
            LOG.debug("executing {} command", cmdName);
            if (buffer != null)
            {
                int channelSent = channel.send(buffer, peer);
                LOG.debug("resuming sending to channel made it send {} bytes", channelSent);
                if (channelSent == 0)
                {
                    LOG.debug("executed {} command; channel sending(1) could not be done", cmdName);
                    return false;
                }
                buffer.clear();
            }
            else
            {
                LOG.debug("fresh command execution");
                buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
                BufferUtil.flipToFill(buffer);
            }

            while (true)
            {
                int quicSent = quicConnection.send(buffer);
                timeoutConsumer.accept(quicConnection.nextTimeout());
                if (quicSent == 0)
                {
                    LOG.debug("executed {} command; all done", cmdName);
                    bufferPool.release(buffer);
                    buffer = null;
                    return true;
                }
                LOG.debug("quiche wants to send {} bytes", quicSent);
                buffer.flip();
                int channelSent = channel.send(buffer, peer);
                LOG.debug("channel sent {} bytes", channelSent);
                if (channelSent == 0)
                {
                    LOG.debug("executed {} command; channel sending(2) could not be done", cmdName);
                    return false;
                }
                buffer.clear();
            }
        }
    }

    private static class ChannelWriteCommand implements Command
    {
        private final ByteBufferPool bufferPool;
        private final ByteBuffer buffer;
        private final DatagramChannel channel;
        private final SocketAddress peer;

        private ChannelWriteCommand(ByteBufferPool bufferPool, ByteBuffer buffer, DatagramChannel channel, SocketAddress peer)
        {
            this.bufferPool = bufferPool;
            this.buffer = buffer;
            this.channel = channel;
            this.peer = peer;
        }

        @Override
        public boolean execute() throws IOException
        {
            LOG.debug("executing channel write command");
            int sent = channel.send(buffer, peer);
            if (sent == 0)
            {
                LOG.debug("executed channel write command; channel sending could not be done");
                return false;
            }
            bufferPool.release(buffer);
            LOG.debug("executed channel write command; all done");
            return true;
        }
    }
}
