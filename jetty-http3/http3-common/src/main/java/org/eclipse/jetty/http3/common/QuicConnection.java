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
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final QuicStreamEndPoint.Factory endpointFactory;
    private final Flusher flusher;
    private final QuicheConnection quicheConnection;
    private final Map<Long, QuicStreamEndPoint> streamEndpoints = new ConcurrentHashMap<>();
    private final InetSocketAddress localAddress;
    private volatile InetSocketAddress remoteAddress;
    private volatile Timeout timeout;
    private volatile boolean markedClosed;

    public QuicConnection(QuicheConnection quicheConnection, InetSocketAddress localAddress, InetSocketAddress remoteAddress, QuicStreamEndPoint.Factory endpointFactory, Flusher flusher)
    {
        this.quicheConnection = quicheConnection;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.endpointFactory = endpointFactory;
        this.flusher = flusher;
    }

    public void dispose()
    {
        quicheConnection.dispose();
    }

    public InetSocketAddress getLocalAddress()
    {
        return localAddress;
    }

    public InetSocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    public int drainCipherText(ByteBuffer cipherText) throws IOException
    {
        int quicSent = quicheConnection.drainCipherText(cipherText);
        long timeoutInMs = quicheConnection.nextTimeout();
        timeout = new Timeout(TimeUnit.MILLISECONDS.toNanos(timeoutInMs));
        LOG.debug("next timeout is in {}ms", timeoutInMs);
        return quicSent;
    }

    /**
     * @param cipherText cipher text
     * @param peer address of the peer who sent the packet
     */
    public int feedCipherText(ByteBuffer cipherText, InetSocketAddress peer) throws IOException
    {
        LOG.debug("handling packet " + BufferUtil.toDetailString(cipherText));
        remoteAddress = peer;

        boolean establishedBefore = quicheConnection.isConnectionEstablished();
        int fed = quicheConnection.feedCipherText(cipherText);
        boolean establishedAfter = quicheConnection.isConnectionEstablished();
        if (!establishedBefore && establishedAfter)
            LOG.debug("newly established connection, negotiated ALPN protocol: '{}'", quicheConnection.getNegotiatedProtocol());
        return fed;
    }

    public void processStreams(Consumer<Runnable> taskProcessor)
    {
        if (quicheConnection.isConnectionEstablished())
        {
            List<Long> readableStreamIds = quicheConnection.readableStreamIds();
            LOG.debug("readable stream ids: {}", readableStreamIds);
            List<Long> writableStreamIds = quicheConnection.writableStreamIds();
            LOG.debug("writable stream ids: {}", writableStreamIds);

            for (Long readableStreamId : readableStreamIds)
            {
                boolean writable = writableStreamIds.remove(readableStreamId);
                QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(readableStreamId);
                if (LOG.isDebugEnabled())
                    LOG.debug("selected endpoint for read{} : {}", (writable ? " and write" : ""), streamEndPoint);
                Runnable task = streamEndPoint.onSelected(true, writable);
                if (task != null)
                    taskProcessor.accept(task);
            }
            for (Long writableStreamId : writableStreamIds)
            {
                QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(writableStreamId);
                LOG.debug("selected endpoint for write : {}", streamEndPoint);
                Runnable task = streamEndPoint.onSelected(false, true);
                if (task != null)
                    taskProcessor.accept(task);
            }
        }
    }

    public String getNegotiatedProtocol()
    {
        return quicheConnection.getNegotiatedProtocol();
    }

    public QuicStreamEndPoint getOrCreateStreamEndPoint(long streamId)
    {
        QuicStreamEndPoint endPoint = streamEndpoints.compute(streamId, (sid, quicStreamEndPoint) ->
        {
            if (quicStreamEndPoint == null)
            {
                quicStreamEndPoint = endpointFactory.createQuicStreamEndPoint(this, sid);
                LOG.debug("creating endpoint for stream {}", sid);
            }
            return quicStreamEndPoint;
        });
        LOG.debug("returning endpoint for stream {}", streamId);
        return endPoint;
    }

    public void onStreamClosed(long streamId)
    {
        streamEndpoints.remove(streamId);
    }

    public boolean isMarkedClosed()
    {
        return markedClosed;
    }

    public void markClosed()
    {
        markedClosed = true;
    }

    public void quicDispose()
    {
        quicheConnection.dispose();
    }

    public void quicOnTimeout()
    {
        quicheConnection.onTimeout();
    }

    public boolean closeQuicConnection() throws IOException
    {
        return quicheConnection.close();
    }

    public boolean isQuicConnectionClosed()
    {
        return quicheConnection.isConnectionClosed();
    }

    public boolean hasConnectionTimedOut()
    {
        return this.timeout.isReached();
    }

    public long streamCapacity(long streamId) throws IOException
    {
        return quicheConnection.streamCapacity(streamId);
    }

    public void writeFinToStream(long streamId) throws IOException
    {
        quicheConnection.feedFinForStream(streamId);
    }

    public int writeToStream(long streamId, ByteBuffer clearText) throws IOException
    {
        return quicheConnection.feedClearTextForStream(streamId, clearText);
    }

    public int readFromStream(long streamId, ByteBuffer clearText) throws IOException
    {
        return quicheConnection.drainClearTextForStream(streamId, clearText);
    }

    public void shutdownStreamInput(long streamId) throws IOException
    {
        quicheConnection.shutdownStream(streamId, false);
    }

    public void shutdownStreamOutput(long streamId) throws IOException
    {
        quicheConnection.shutdownStream(streamId, true);
    }

    public boolean isStreamFinished(long streamId)
    {
        return quicheConnection.isStreamFinished(streamId);
    }

    public void flush()
    {
        flusher.flush(this);
    }

    private static class Timeout
    {
        private final long timestampInNs;
        private final long timeoutInNs;

        private Timeout(long timeoutInNs)
        {
            this.timestampInNs = System.nanoTime();
            this.timeoutInNs = timeoutInNs;
        }

        public boolean isReached()
        {
            return System.nanoTime() - timestampInNs >= timeoutInNs;
        }
    }

    public interface Flusher
    {
        void flush(QuicConnection quicConnection);
    }
}
