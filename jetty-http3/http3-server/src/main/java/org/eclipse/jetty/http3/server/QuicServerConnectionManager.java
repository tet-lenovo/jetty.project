package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.common.CommandManager;
import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicConnectionManager;
import org.eclipse.jetty.http3.common.QuicStreamEndPoint;
import org.eclipse.jetty.http3.quic.QuicheConfig;
import org.eclipse.jetty.http3.quic.QuicheConnection;
import org.eclipse.jetty.http3.quic.QuicheConnectionId;
import org.eclipse.jetty.http3.quic.quiche.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicServerConnectionManager extends QuicConnectionManager
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicServerConnectionManager.class);

    public QuicServerConnectionManager(LifeCycle lifeCycle, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, QuicStreamEndPoint.Factory endpointFactory, QuicheConfig quicheConfig) throws IOException
    {
        super(lifeCycle, executor, scheduler, bufferPool, endpointFactory, quicheConfig);
    }

    @Override
    protected QuicConnection onNewConnection(ByteBuffer buffer, SocketAddress peer, QuicheConnectionId connectionId, QuicStreamEndPoint.Factory endpointFactory) throws IOException
    {
        ByteBufferPool bufferPool = getByteBufferPool();
        DatagramChannel channel = getChannel();
        QuicheConfig quicheConfig = getQuicheConfig();
        CommandManager commandManager = getCommandManager();

        boolean needWrite;
        QuicConnection connection = null;
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
                needWrite = commandManager.channelWrite(channel, negociationBuffer, peer);
            }
            else
            {
                bufferPool.release(negociationBuffer);
                needWrite = false;
            }
        }
        else
        {
            LOG.debug("new connection accepted");
            connection = new QuicConnection(acceptedQuicheConnection, (InetSocketAddress)channel.getLocalAddress(), (InetSocketAddress)peer, endpointFactory);
            needWrite = commandManager.quicSend(connection, channel);
        }

        changeInterest(needWrite);
        return connection;
    }
}
