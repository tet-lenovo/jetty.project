package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import org.eclipse.jetty.http3.quic.QuicConnection;
import org.eclipse.jetty.http3.quic.QuicStream;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicEndPointManager
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicEndPointManager.class);

    private final QuicConnector quicConnector;
    private final QuicConnection quicConnection;
    private final Map<Long, QuicStreamEndPoint> streamEndpoints = new ConcurrentHashMap<>();
    private final InetSocketAddress localAddress;
    private volatile InetSocketAddress remoteAddress;
    private volatile long registrationTsInNs;
    private volatile long timeoutInNs;
    private volatile boolean markedClosed;

    protected QuicEndPointManager(QuicConnection quicConnection, InetSocketAddress localAddress, InetSocketAddress remoteAddress, QuicConnector quicConnector)
    {
        this.quicConnection = quicConnection;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.quicConnector = quicConnector;
    }

    public void dispose()
    {
        quicConnection.dispose();
    }

    public InetSocketAddress getLocalAddress()
    {
        return localAddress;
    }

    public InetSocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    public LongConsumer getTimeoutSetter()
    {
        return timeoutInMs ->
        {
            registrationTsInNs = System.nanoTime();
            timeoutInNs = TimeUnit.MILLISECONDS.toNanos(timeoutInMs);
            LOG.debug("next timeout is in {}ms", timeoutInMs);
        };
    }

    /**
     * @param buffer cipher text
     * @param peer address of the peer who sent the packet
     * @param bufferPool the pool where to release the buffer when done with it
     */
    public void handlePacket(ByteBuffer buffer, InetSocketAddress peer, ByteBufferPool bufferPool) throws IOException
    {
        LOG.debug("handling packet " + BufferUtil.toDetailString(buffer));
        remoteAddress = peer;

        boolean establishedBefore = quicConnection.isConnectionEstablished();
        quicConnection.recv(buffer);
        bufferPool.release(buffer);
        boolean establishedAfter = quicConnection.isConnectionEstablished();
        if (!establishedBefore && establishedAfter)
            LOG.debug("newly established connection, negotiated ALPN protocol : {}", quicConnection.getNegotiatedProtocol());

        if (establishedAfter)
        {
            Iterator<QuicStream> it = quicConnection.readableStreamsIterator();
            while (it.hasNext())
            {
                QuicStream stream = it.next();
                long streamId = stream.getStreamId();
                LOG.debug("stream {} is readable", streamId);

                QuicStreamEndPoint streamEndPoint = streamEndpoints.compute(streamId, (sid, quicStreamEndPoint) ->
                {
                    if (quicStreamEndPoint == null)
                        quicStreamEndPoint = quicConnector.createQuicStreamEndPoint(QuicEndPointManager.this, sid);
                    return quicStreamEndPoint;
                });

                if (streamEndPoint.fillInterested.compareAndSet(true, false))
                {
                    LOG.debug("Fillable");
                    streamEndPoint.getFillInterest().fillable();
                }
            }
        }
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

    public boolean closeQuicConnection() throws IOException
    {
        return quicConnection.close();
    }

    public boolean isQuicConnectionClosed()
    {
        return quicConnection.isConnectionClosed();
    }

    public QuicConnection getQuicConnection()
    {
        return quicConnection;
    }

    public boolean hasQuicConnectionTimedOut()
    {
        return System.nanoTime() - registrationTsInNs >= timeoutInNs;
    }

    public QuicStream quicWritableStream(long streamId)
    {
        Iterator<QuicStream> it = quicConnection.writableStreamsIterator();
        while (it.hasNext())
        {
            QuicStream stream = it.next();
            if (stream.getStreamId() == streamId)
                return stream;
        }
        return null;
    }

    public QuicStream quicReadableStream(long streamId)
    {
        Iterator<QuicStream> it = quicConnection.readableStreamsIterator();
        while (it.hasNext())
        {
            QuicStream stream = it.next();
            if (stream.getStreamId() == streamId)
                return stream;
        }
        return null;
    }
}
