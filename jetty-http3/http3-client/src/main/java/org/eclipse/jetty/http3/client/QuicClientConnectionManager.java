package org.eclipse.jetty.http3.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicConnectionManager;
import org.eclipse.jetty.http3.common.QuicStreamEndPoint;
import org.eclipse.jetty.http3.quic.QuicheConfig;
import org.eclipse.jetty.http3.quic.QuicheConnection;
import org.eclipse.jetty.http3.quic.QuicheConnectionId;
import org.eclipse.jetty.http3.quic.quiche.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;

public class QuicClientConnectionManager extends QuicConnectionManager
{
    public QuicClientConnectionManager(LifeCycle lifeCycle, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, QuicStreamEndPoint.Factory endpointFactory, QuicheConfig quicheConfig) throws IOException
    {
        super(lifeCycle, executor, scheduler, bufferPool, endpointFactory, quicheConfig);
    }

    @Override
    protected boolean onNewConnection(ByteBuffer buffer, SocketAddress peer, QuicheConnectionId connectionId, QuicStreamEndPoint.Factory endpointFactory) throws IOException
    {
        ConnectingHolder connectingHolder = connecting.get(peer);
        if (connectingHolder == null)
            return false;

        QuicheConnection quicheConnection = connectingHolder.quicheConnection;
        buffer.mark();
        quicheConnection.recv(buffer);

        if (quicheConnection.isConnectionEstablished())
        {
            connecting.remove(peer);
            buffer.reset();
            QuicheConnectionId quicheConnectionId = QuicheConnectionId.fromPacket(buffer);
            QuicConnection quicConnection = new QuicConnection(quicheConnection, (InetSocketAddress)getChannel().getLocalAddress(), (InetSocketAddress)peer, endpointFactory);
            addConnection(quicheConnectionId, quicConnection);

            QuicStreamEndPoint quicStreamEndPoint = quicConnection.newStream(4); // TODO generate a proper stream ID
            Connection connection = connectingHolder.httpClientTransportOverQuic.newConnection(quicStreamEndPoint, connectingHolder.context);
            quicStreamEndPoint.setConnection(connection);
            connection.onOpen();
        }

        buffer.clear();
        quicheConnection.send(buffer);
        buffer.flip();

        return getCommandManager().channelWrite(getChannel(), buffer, peer);
    }

    private final Map<SocketAddress, ConnectingHolder> connecting = new ConcurrentHashMap<>();

    public void connect(InetSocketAddress target, Map<String, Object> context, HttpClientTransportOverQuic httpClientTransportOverQuic) throws IOException
    {
        QuicheConnection connection = QuicheConnection.connect(getQuicheConfig(), target);
        ByteBufferPool bufferPool = getByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
        BufferUtil.flipToFill(buffer);
        connection.send(buffer);
        buffer.flip();
        connecting.put(target, new ConnectingHolder(connection, context, httpClientTransportOverQuic));
        boolean queued = getCommandManager().channelWrite(getChannel(), buffer, target);
        if (queued)
            changeInterest(true);
    }

    private static class ConnectingHolder
    {
        final QuicheConnection quicheConnection;
        final Map<String, Object> context;
        final HttpClientTransportOverQuic httpClientTransportOverQuic;

        private ConnectingHolder(QuicheConnection quicheConnection, Map<String, Object> context, HttpClientTransportOverQuic httpClientTransportOverQuic)
        {
            this.quicheConnection = quicheConnection;
            this.context = context;
            this.httpClientTransportOverQuic = httpClientTransportOverQuic;
        }
    }
}
