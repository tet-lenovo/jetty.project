package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongConsumer;

import org.eclipse.jetty.http3.quic.QuicConnection;
import org.eclipse.jetty.http3.quic.quiche.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandManager
{
    private static final Logger LOG = LoggerFactory.getLogger(CommandManager.class);

    private final Deque<Command> commands = new ArrayDeque<>();
    private final ByteBufferPool bufferPool;

    public CommandManager(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
    }

    /**
     * @return true if the command was immediately processed, false if it was queued.
     */
    public boolean channelWrite(ByteBuffer newConnectionNegotiationToSend, DatagramChannel channel, SocketAddress peer) throws IOException
    {
        ChannelWriteCommand channelWriteCommand = new ChannelWriteCommand(newConnectionNegotiationToSend, channel, peer);
        if (!channelWriteCommand.execute())
        {
            commands.offer(channelWriteCommand);
            return true;
        }
        return false;
    }

    /**
     * @return true if the command was immediately processed, false if it was queued.
     */
    public boolean quicSend(DatagramChannel channel, QuicEndPointManager endPointManager) throws IOException
    {
        QuicSendCommand quicSendCommand = new QuicSendCommand(channel, endPointManager);
        if (!quicSendCommand.execute())
        {
            commands.offer(quicSendCommand);
            return true;
        }
        return false;
    }

    /**
     * @return true if the command was immediately processed, false if it was queued.
     */
    public boolean quicTimeout(QuicEndPointManager quicEndPointManager, DatagramChannel channel, boolean closed) throws IOException
    {
        QuicTimeoutCommand quicTimeoutCommand = new QuicTimeoutCommand(quicEndPointManager, channel, closed);
        if (!quicTimeoutCommand.execute())
        {
            commands.offer(quicTimeoutCommand);
            return true;
        }
        return false;
    }

    /**
     * @return true if all commands were processed, false otherwise.
     */
    public boolean processQueue() throws IOException
    {
        LOG.debug("processing commands " + commands);
        while (!commands.isEmpty())
        {
            Command command = commands.poll();
            LOG.debug("executing command " + command);
            boolean finished = command.execute();
            LOG.debug("executed command; finished? " + finished);
            if (!finished)
            {
                commands.offer(command);
                return true;
            }
        }
        return false;
    }


    private interface Command
    {
        boolean execute() throws IOException;
    }

    private class QuicTimeoutCommand implements Command
    {
        private final QuicSendCommand quicSendCommand;
        private final boolean close;
        private boolean timeoutCalled;

        public QuicTimeoutCommand(QuicEndPointManager quicEndPointManager, DatagramChannel channel, boolean close)
        {
            this.close = close;
            this.quicSendCommand = new QuicSendCommand("timeout", channel, quicEndPointManager);
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
                LOG.debug("disposing of quiche connection");
                quicSendCommand.quicConnection.dispose();
            }
            return true;
        }
    }

    private class QuicSendCommand implements Command
    {
        private final String cmdName;
        private final QuicConnection quicConnection;
        private final DatagramChannel channel;
        private final SocketAddress peer;
        private final LongConsumer timeoutConsumer;

        private ByteBuffer buffer;

        public QuicSendCommand(DatagramChannel channel, QuicEndPointManager quicEndPointManager)
        {
            this("send", channel, quicEndPointManager);
        }

        private QuicSendCommand(String cmdName, DatagramChannel channel, QuicEndPointManager quicEndPointManager)
        {
            this.cmdName = cmdName;
            this.quicConnection = quicEndPointManager.getQuicConnection();
            this.channel = channel;
            this.peer = quicEndPointManager.getRemoteAddress();
            this.timeoutConsumer = quicEndPointManager.getTimeoutSetter();
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

    private class ChannelWriteCommand implements Command
    {
        private final ByteBuffer buffer;
        private final DatagramChannel channel;
        private final SocketAddress peer;

        private ChannelWriteCommand(ByteBuffer buffer, DatagramChannel channel, SocketAddress peer)
        {
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
