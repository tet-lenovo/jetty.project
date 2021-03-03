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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.IClientConnector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

public class QuicClientConnector extends ContainerLifeCycle implements IClientConnector
{
    private final QuicheConfig quicheConfig;
    private Executor executor;
    private Scheduler scheduler;
    private boolean connectBlocking;
    private SocketAddress bindAddress;
    private ByteBufferPool byteBufferPool;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration idleTimeout = Duration.ofSeconds(30);
    private QuicClientConnectionManager quicConnectionManager;

    public QuicClientConnector(String httpVersion)
    {
        quicheConfig = new QuicheConfig();
        quicheConfig.setApplicationProtos(httpVersion);
        quicheConfig.setMaxIdleTimeout(5000L);
        quicheConfig.setInitialMaxData(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        quicheConfig.setInitialMaxStreamDataUni(10000000L);
        quicheConfig.setInitialMaxStreamsBidi(100L);
        quicheConfig.setInitialMaxStreamsUni(100L);
        quicheConfig.setDisableActiveMigration(true);
        quicheConfig.setVerifyPeer(false);
    }

    public void connect(InetSocketAddress address, Map<String, Object> context, HttpClientTransportOverQuic httpClientTransportOverQuic)
    {
        Promise<?> promise = (Promise<?>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
        try
        {
            quicConnectionManager.connect(address, context, httpClientTransportOverQuic);
        }
        catch (IOException e)
        {
            promise.failed(e);
        }
    }

    @Override
    public SslContextFactory.Client getSslContextFactory()
    {
        return new SslContextFactory.Client();
    }

    @Override
    public Executor getExecutor()
    {
        return executor;
    }

    @Override
    public void setExecutor(Executor executor)
    {
        if (isStarted())
            throw new IllegalStateException();
        updateBean(this.executor, executor);
        this.executor = executor;
    }

    @Override
    public Scheduler getScheduler()
    {
        return scheduler;
    }

    @Override
    public void setScheduler(Scheduler scheduler)
    {
        if (isStarted())
            throw new IllegalStateException();
        updateBean(this.scheduler, scheduler);
        this.scheduler = scheduler;
    }

    @Override
    public boolean isConnectBlocking()
    {
        return connectBlocking;
    }

    @Override
    public void setConnectBlocking(boolean connectBlocking)
    {
        this.connectBlocking = connectBlocking;
    }

    @Override
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    @Override
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    @Override
    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        if (isStarted())
            throw new IllegalStateException();
        updateBean(this.byteBufferPool, byteBufferPool);
        this.byteBufferPool = byteBufferPool;
    }

    @Override
    public Duration getIdleTimeout()
    {
        return idleTimeout;
    }

    @Override
    public void setIdleTimeout(Duration idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    @Override
    public Duration getConnectTimeout()
    {
        return connectTimeout;
    }

    @Override
    public void setConnectTimeout(Duration connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (executor == null)
        {
            QueuedThreadPool clientThreads = new QueuedThreadPool();
            clientThreads.setName(String.format("client-pool@%x", hashCode()));
            setExecutor(clientThreads);
        }
        if (scheduler == null)
            setScheduler(new ScheduledExecutorScheduler(String.format("client-scheduler@%x", hashCode()), false));
        if (byteBufferPool == null)
            setByteBufferPool(new MappedByteBufferPool());
        super.doStart();

        quicConnectionManager = new QuicClientConnectionManager(this, executor, scheduler, byteBufferPool, (quicConnection, streamId) -> new QuicClientStreamEndPoint(getScheduler(), quicConnection, streamId), quicheConfig);
        quicConnectionManager.start();
    }

    @Override
    protected void doStop() throws Exception
    {
        quicConnectionManager.close();
        super.doStop();
    }
}
