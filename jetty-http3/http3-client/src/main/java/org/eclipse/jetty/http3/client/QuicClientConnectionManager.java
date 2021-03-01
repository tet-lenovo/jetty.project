package org.eclipse.jetty.http3.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.http3.common.CommandManager;
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
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicClientConnectionManager extends QuicConnectionManager
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicClientConnectionManager.class);

    public QuicClientConnectionManager(Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, QuicStreamEndPoint.Factory endpointFactory, QuicheConfig quicheConfig) throws IOException
    {
        super(executor, scheduler, bufferPool, endpointFactory, quicheConfig);
    }

    @Override
    protected boolean onNewConnection(ByteBuffer buffer, SocketAddress peer, QuicheConnectionId connectionId, QuicStreamEndPoint.Factory endpointFactory) throws IOException
    {
        ConnectingHolder connectingHolder = connecting.get(peer);
        QuicheConnection quicheConnection = connectingHolder.quicheConnection;
        if (quicheConnection == null)
            return false;

        QuicheConnectionId quicheConnectionId = QuicheConnectionId.fromPacket(buffer);
        quicheConnection.recv(buffer);

        if (quicheConnection.isConnectionEstablished())
        {
            QuicConnection connection = new QuicConnection(quicheConnection, (InetSocketAddress)getChannel().getLocalAddress(), (InetSocketAddress)peer, endpointFactory);
            addConnection(quicheConnectionId, connection);

            QuicStreamEndPoint quicStreamEndPoint = connection.newStream(4);

            Connection c = connectingHolder.httpClientTransportOverQuic.newConnection(quicStreamEndPoint, connectingHolder.context);
            Promise<Connection> promise = (Promise<Connection>)connectingHolder.context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
            connecting.remove(peer);
            promise.succeeded(c);
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
