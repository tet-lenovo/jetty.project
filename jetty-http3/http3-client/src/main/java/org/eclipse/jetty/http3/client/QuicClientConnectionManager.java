//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

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
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;

public class QuicClientConnectionManager extends QuicConnectionManager
{
    private final Map<SocketAddress, ConnectingHolder> pendingConnections = new ConcurrentHashMap<>();

    public QuicClientConnectionManager(LifeCycle lifeCycle, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, QuicStreamEndPoint.Factory endpointFactory, QuicheConfig quicheConfig) throws IOException
    {
        super(lifeCycle, executor, scheduler, bufferPool, endpointFactory, quicheConfig);
    }

    @Override
    protected QuicConnection onNewConnection(ByteBuffer buffer, SocketAddress peer, QuicheConnectionId quicheConnectionId, QuicStreamEndPoint.Factory endpointFactory) throws IOException
    {
        ConnectingHolder connectingHolder = pendingConnections.get(peer);
        if (connectingHolder == null)
            return null;

        QuicheConnection quicheConnection = connectingHolder.quicheConnection;
        quicheConnection.recv(buffer);

        QuicConnection quicConnection = null;
        if (quicheConnection.isConnectionEstablished())
        {
            pendingConnections.remove(peer);
            quicConnection = new QuicConnection(quicheConnection, (InetSocketAddress)getChannel().getLocalAddress(), (InetSocketAddress)peer, endpointFactory);

            QuicStreamEndPoint quicStreamEndPoint = quicConnection.getOrCreateStreamEndPoint(4); // TODO generate a proper stream ID
            Connection connection = connectingHolder.httpClientTransportOverQuic.newConnection(quicStreamEndPoint, connectingHolder.context);
            // TODO configure the connection, see other transports
            quicStreamEndPoint.setConnection(connection);

            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)connectingHolder.context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
            try
            {
                quicStreamEndPoint.onOpen();
                connection.onOpen();
                promise.succeeded(connection);
            }
            catch (Throwable t)
            {
                promise.failed(t);
            }
        }

        ByteBuffer sendBuffer = getByteBufferPool().acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
        BufferUtil.flipToFill(sendBuffer);
        quicheConnection.send(sendBuffer);
        sendBuffer.flip();

        boolean queued = getCommandManager().channelWrite(getChannel(), sendBuffer, peer);
        if (queued)
            changeInterest(true);
        return quicConnection;
    }

    public void connect(InetSocketAddress target, Map<String, Object> context, HttpClientTransportOverQuic httpClientTransportOverQuic) throws IOException
    {
        QuicheConnection connection = QuicheConnection.connect(getQuicheConfig(), target);
        ByteBufferPool bufferPool = getByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
        BufferUtil.flipToFill(buffer);
        connection.send(buffer);
        //connection.nextTimeout(); // TODO quiche timeout handling is missing for pending connections
        buffer.flip();
        pendingConnections.put(target, new ConnectingHolder(connection, context, httpClientTransportOverQuic));
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
