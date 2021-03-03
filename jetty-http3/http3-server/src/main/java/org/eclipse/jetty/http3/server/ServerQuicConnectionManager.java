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
import java.nio.channels.DatagramChannel;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.common.CommandManager;
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
    protected Map.Entry<QuicConnection, Boolean> onNewConnection(ByteBuffer buffer, SocketAddress peer, QuicheConnectionId connectionId, QuicStreamEndPoint.Factory endpointFactory) throws IOException
    {
        ByteBufferPool bufferPool = getByteBufferPool();
        DatagramChannel channel = getChannel();
        QuicheConfig quicheConfig = getQuicheConfig();
        CommandManager commandManager = getCommandManager();

        boolean needWrite;
        QuicConnection quicConnection;
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
            quicConnection = null;
        }
        else
        {
            LOG.debug("new connection accepted");
            quicConnection = new QuicConnection(acceptedQuicheConnection, (InetSocketAddress)channel.getLocalAddress(), (InetSocketAddress)peer, endpointFactory);
            needWrite = commandManager.quicSend(quicConnection, channel);
        }
        return new AbstractMap.SimpleImmutableEntry<>(quicConnection, needWrite);
    }
}
