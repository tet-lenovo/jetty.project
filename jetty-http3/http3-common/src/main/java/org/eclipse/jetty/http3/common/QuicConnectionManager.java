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

package org.eclipse.jetty.http3.common;

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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicConnectionManager
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnectionManager.class);

    private final LifeCycle lifeCycle;
    private final Executor executor;
    private final Scheduler scheduler;
    private final ByteBufferPool bufferPool;
    private final QuicStreamEndPoint.Factory endpointFactory;

    private final Map<QuicheConnectionId, QuicConnection> connections = new ConcurrentHashMap<>();
    private CommandManager commandManager;
    private Selector selector;
    private DatagramChannel channel;
    private SelectionKey selectionKey;
    private QuicheConfig quicheConfig;

    public QuicConnectionManager(LifeCycle lifeCycle, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, QuicStreamEndPoint.Factory endpointFactory, QuicheConfig quicheConfig) throws IOException
    {
        this.lifeCycle = lifeCycle;
        this.executor = executor;
        this.scheduler = scheduler;
        this.bufferPool = bufferPool;
        this.endpointFactory = endpointFactory;

        this.selector = Selector.open();
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        this.selectionKey = this.channel.register(selector, SelectionKey.OP_READ);
        this.quicheConfig = quicheConfig;
        this.commandManager = new CommandManager(getByteBufferPool(), this.channel);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public Object getTransport()
    {
        return channel;
    }

    public QuicheConfig getQuicheConfig()
    {
        return quicheConfig;
    }

    public boolean isOpen()
    {
        return channel != null && channel.isOpen();
    }

    public void start()
    {
        scheduler.schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);
        executor.execute(this::selectLoop);
    }

    public void close()
    {
        if (selector == null)
            return;

        connections.values().forEach(QuicConnection::dispose);
        connections.clear();
        selectionKey.cancel();
        selectionKey = null;
        IO.close(channel);
        channel = null;
        IO.close(selector);
        selector = null;
        quicheConfig = null;
        commandManager = null;
    }

    private void fireTimeoutNotificationIfNeeded()
    {
        boolean timedOut = connections.values().stream().map(QuicConnection::hasQuicConnectionTimedOut).findFirst().orElse(false);
        if (timedOut)
        {
            LOG.debug("connection timed out, waking up selector");
            selector.wakeup();
        }
        scheduler.schedule(this::fireTimeoutNotificationIfNeeded, 100, TimeUnit.MILLISECONDS);
    }

    private void selectLoop()
    {
        String oldName = Thread.currentThread().getName();
        Thread.currentThread().setName("jetty-" + getClass().getSimpleName());
        while (true)
        {
            try
            {
                select();
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
    }

    private void select() throws IOException, InterruptedException
    {
        int selected = selector.select();
        if (Thread.interrupted())
            throw new InterruptedException("Selector thread was interrupted");
        if (!lifeCycle.isRunning())
            throw new InterruptedException("Container stopped");

        if (selected == 0)
        {
            LOG.debug("no selected key");
            processTimeout();
        }
        else
        {
            Iterator<SelectionKey> selectorIt = selector.selectedKeys().iterator();
            while (selectorIt.hasNext())
            {
                SelectionKey key = selectorIt.next();
                selectorIt.remove();
                LOG.debug("Processing selected key {}", key);

                boolean readable = key.isReadable();
                LOG.debug("key is readable? {}", readable);
                if (readable)
                    processReadableKey();

                boolean writable = key.isWritable();
                LOG.debug("key is writable? {}", writable);
                if (writable)
                    processWritableKey();

                LOG.debug("Processed selected key {}", key);
            }
        }
        changeInterest(commandManager.needWrite());
    }

    private void processTimeout() throws IOException
    {
        Iterator<QuicConnection> it = connections.values().iterator();
        while (it.hasNext())
        {
            QuicConnection quicConnection = it.next();
            if (quicConnection.hasQuicConnectionTimedOut())
            {
                LOG.debug("connection has timed out: " + quicConnection);
                boolean closed = quicConnection.isQuicConnectionClosed();
                if (closed)
                {
                    quicConnection.markClosed();
                    it.remove();
                    LOG.debug("connection closed due to timeout; remaining connections: " + connections);
                }
                commandManager.quicTimeout(quicConnection, closed);
            }
        }
    }

    private void processReadableKey() throws IOException
    {
        ByteBufferPool bufferPool = getByteBufferPool();

        ByteBuffer buffer = bufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
        BufferUtil.flipToFill(buffer);
        InetSocketAddress peer = (InetSocketAddress)channel.receive(buffer);
        buffer.flip();

        QuicheConnectionId connectionId = QuicheConnectionId.fromPacket(buffer);
        QuicConnection quicConnection = connections.get(connectionId);
        if (quicConnection == null)
        {
            quicConnection = createConnection(buffer, peer, connectionId);
            if (quicConnection != null)
            {
                commandManager.quicSend(quicConnection);
                connections.put(connectionId, quicConnection);
            }
        }
        else
        {
            LOG.debug("got packet for an existing connection: " + connectionId + " - buffer: p=" + buffer.position() + " r=" + buffer.remaining());
            // existing connection
            quicConnection.quicRecv(buffer, peer);
            commandManager.quicSend(quicConnection);
        }
        bufferPool.release(buffer);
    }

    private void processWritableKey() throws IOException
    {
        commandManager.processQueue();
    }

    private void changeInterest(boolean needWrite)
    {
        int ops = SelectionKey.OP_READ | (needWrite ? SelectionKey.OP_WRITE : 0);
        if (selectionKey.interestOps() == ops)
        {
            LOG.debug("interest already at {}, no change needed", interestToString(ops));
            return;
        }
        LOG.debug("setting key interest to {}", interestToString(ops));
        selectionKey.interestOps(ops);
    }

    private static String interestToString(int ops)
    {
        String interest = "READ";
        if ((ops & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE)
            interest += "|WRITE";
        return interest;
    }

    protected abstract QuicConnection createConnection(ByteBuffer buffer, InetSocketAddress peer, QuicheConnectionId connectionId) throws IOException;

    protected QuicStreamEndPoint.Factory getEndpointFactory()
    {
        return endpointFactory;
    }

    protected InetSocketAddress getLocalAddress() throws IOException
    {
        return (InetSocketAddress)channel.getLocalAddress();
    }

    protected void bind(SocketAddress bindAddress) throws IOException
    {
        channel.bind(bindAddress);
    }

    protected void channelWrite(ByteBuffer buffer, SocketAddress peer) throws IOException
    {
        commandManager.channelWrite(buffer, peer);
    }

    protected void wakeupSelectorIfNeeded()
    {
        if (commandManager.needWrite())
            selector.wakeup();
    }
}
