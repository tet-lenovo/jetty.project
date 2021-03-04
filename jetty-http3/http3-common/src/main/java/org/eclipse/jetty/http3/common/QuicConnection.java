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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheStream;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final QuicStreamEndPoint.Factory endpointFactory;
    private final QuicheConnection quicheConnection;
    private final Map<Long, QuicStreamEndPoint> streamEndpoints = new ConcurrentHashMap<>();
    private final InetSocketAddress localAddress;
    private volatile InetSocketAddress remoteAddress;
    private volatile long sendTimestampInNs;
    private volatile long sendTimeoutInNs;
    private volatile boolean markedClosed;

    public QuicConnection(QuicheConnection quicheConnection, InetSocketAddress localAddress, InetSocketAddress remoteAddress, QuicStreamEndPoint.Factory endpointFactory)
    {
        this.quicheConnection = quicheConnection;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.endpointFactory = endpointFactory;
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

    public int quicSend(ByteBuffer buffer) throws IOException
    {
        int quicSent = quicheConnection.send(buffer);
        long timeoutInMs = quicheConnection.nextTimeout();
        sendTimestampInNs = System.nanoTime();
        sendTimeoutInNs = TimeUnit.MILLISECONDS.toNanos(timeoutInMs);
        LOG.debug("next timeout is in {}ms", timeoutInMs);
        return quicSent;
    }

    /**
     * @param buffer cipher text
     * @param peer address of the peer who sent the packet
     * @return a collection of QuicStreamEndPoints that need to be notified they have data to read
     */
    public Collection<QuicStreamEndPoint> quicRecv(ByteBuffer buffer, InetSocketAddress peer) throws IOException
    {
        LOG.debug("handling packet " + BufferUtil.toDetailString(buffer));
        remoteAddress = peer;

        boolean establishedBefore = quicheConnection.isConnectionEstablished();
        quicheConnection.recv(buffer);
        boolean establishedAfter = quicheConnection.isConnectionEstablished();
        if (!establishedBefore && establishedAfter)
            LOG.debug("newly established connection, negotiated ALPN protocol: '{}'", quicheConnection.getNegotiatedProtocol());

        Collection<QuicStreamEndPoint> result = new ArrayList<>();
        if (establishedAfter)
        {
            Iterator<QuicheStream> it = quicheConnection.readableStreamsIterator();
            while (it.hasNext())
            {
                QuicheStream stream = it.next();
                long streamId = stream.getStreamId();
                LOG.debug("stream {} is readable", streamId);

                QuicStreamEndPoint streamEndPoint = getOrCreateStreamEndPoint(streamId);
                result.add(streamEndPoint);
            }
        }
        return result;
    }

    public String getNegotiatedProtocol()
    {
        return quicheConnection.getNegotiatedProtocol();
    }

    public QuicStreamEndPoint getOrCreateStreamEndPoint(long streamId)
    {
        return streamEndpoints.compute(streamId, (sid, quicStreamEndPoint) ->
        {
            if (quicStreamEndPoint == null)
                quicStreamEndPoint = endpointFactory.createQuicStreamEndPoint(this, sid);
            return quicStreamEndPoint;
        });
    }

    public void onStreamClosed(long streamId)
    {
        streamEndpoints.remove(streamId);
        if (streamEndpoints.isEmpty())
            markClosed();
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

    public boolean hasQuicConnectionTimedOut()
    {
        return System.nanoTime() - sendTimestampInNs >= sendTimeoutInNs;
    }

    public int writeToStream(long streamId, ByteBuffer buffer) throws IOException
    {
        return quicheConnection.writeToStream(streamId, buffer);
    }

    public int readFromStream(long streamId, ByteBuffer buffer) throws IOException
    {
        return quicheConnection.readFromStream(streamId, buffer);
    }

    public boolean isFinished(long streamId)
    {
        return quicheConnection.isStreamFinished(streamId);
    }
}
