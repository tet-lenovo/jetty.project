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

package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicConnectionManager;
import org.eclipse.jetty.http3.common.QuicStreamEndPoint;
import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerQuicConnectionManager extends QuicConnectionManager
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicConnectionManager.class);

    public ServerQuicConnectionManager(LifeCycle lifeCycle, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, QuicStreamEndPoint.Factory endpointFactory, QuicheConfig quicheConfig) throws IOException
    {
        super(lifeCycle, executor, scheduler, bufferPool, endpointFactory, quicheConfig);
    }

    @Override
    public void bind(SocketAddress bindAddress) throws IOException
    {
        super.bind(bindAddress);
    }

    @Override
    protected QuicConnection createConnection(ByteBuffer buffer, InetSocketAddress peer, QuicheConnectionId connectionId) throws IOException
    {
        QuicheConfig quicheConfig = getQuicheConfig();

        QuicConnection quicConnection;
        LOG.debug("got packet for a new connection");
        QuicheConnection acceptedQuicheConnection = QuicheConnection.tryAccept(quicheConfig, peer, buffer);
        if (acceptedQuicheConnection == null)
        {
            LOG.debug("accepting connection failed, trying negotiation");
            ByteBufferPool bufferPool = getByteBufferPool();
            ByteBuffer negotiationBuffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
            BufferUtil.flipToFill(negotiationBuffer);
            if (QuicheConnection.negotiate(peer, buffer, negotiationBuffer))
            {
                LOG.debug("negotiation possible, writing proposal");
                channelWrite(negotiationBuffer, peer);
            }
            else
            {
                LOG.debug("negotiation not possible, ignoring connection attempt");
                bufferPool.release(negotiationBuffer);
            }
            quicConnection = null;
        }
        else
        {
            LOG.debug("new connection accepted");
            quicConnection = new QuicConnection(acceptedQuicheConnection, getLocalAddress(), peer, getEndpointFactory());
        }
        return quicConnection;
    }
}
