package org.eclipse.jetty.http3.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http3.quic.QuicheConnection;
import org.eclipse.jetty.http3.quic.QuicheStream;
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
     */
    public void quicRecv(ByteBuffer buffer, InetSocketAddress peer) throws IOException
    {
        LOG.debug("handling packet " + BufferUtil.toDetailString(buffer));
        remoteAddress = peer;

        boolean establishedBefore = quicheConnection.isConnectionEstablished();
        quicheConnection.recv(buffer);
        boolean establishedAfter = quicheConnection.isConnectionEstablished();
        if (!establishedBefore && establishedAfter)
            LOG.debug("newly established connection, negotiated ALPN protocol : {}", quicheConnection.getNegotiatedProtocol());

        if (establishedAfter)
        {
            Iterator<QuicheStream> it = quicheConnection.readableStreamsIterator();
            while (it.hasNext())
            {
                QuicheStream stream = it.next();
                long streamId = stream.getStreamId();
                LOG.debug("stream {} is readable", streamId);

                QuicStreamEndPoint streamEndPoint = newStream(streamId);
                streamEndPoint.onFillable();
            }
        }
    }

    public QuicStreamEndPoint newStream(long streamId)
    {
        QuicStreamEndPoint streamEndPoint = streamEndpoints.compute(streamId, (sid, quicStreamEndPoint) ->
        {
            if (quicStreamEndPoint == null)
                quicStreamEndPoint = endpointFactory.createQuicStreamEndPoint(QuicConnection.this, sid);
            return quicStreamEndPoint;
        });
        return streamEndPoint;
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

    public QuicheStream quicWritableStream(long streamId)
    {
        Iterator<QuicheStream> it = quicheConnection.writableStreamsIterator();
        while (it.hasNext())
        {
            QuicheStream stream = it.next();
            if (stream.getStreamId() == streamId)
                return stream;
        }
        return null;
    }

    public QuicheStream quicReadableStream(long streamId)
    {
        Iterator<QuicheStream> it = quicheConnection.readableStreamsIterator();
        while (it.hasNext())
        {
            QuicheStream stream = it.next();
            if (stream.getStreamId() == streamId)
                return stream;
        }
        return null;
    }

    public int writeToStream(long streamId, ByteBuffer buffer) throws IOException
    {
        return quicheConnection.writeToStream(streamId, buffer);
    }
}
