package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import org.eclipse.jetty.http3.quic.QuicheConnection;
import org.eclipse.jetty.http3.quic.QuicheStream;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnection.class);

    private final QuicConnector quicConnector;
    private final QuicheConnection quicheConnection;
    private final Map<Long, QuicStreamEndPoint> streamEndpoints = new ConcurrentHashMap<>();
    private final InetSocketAddress localAddress;
    private volatile InetSocketAddress remoteAddress;
    private volatile long registrationTsInNs;
    private volatile long timeoutInNs;
    private volatile boolean markedClosed;

    protected QuicConnection(QuicheConnection quicheConnection, InetSocketAddress localAddress, InetSocketAddress remoteAddress, QuicConnector quicConnector)
    {
        this.quicheConnection = quicheConnection;
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.quicConnector = quicConnector;
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

        boolean establishedBefore = quicheConnection.isConnectionEstablished();
        quicheConnection.recv(buffer);
        bufferPool.release(buffer);
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

                QuicStreamEndPoint streamEndPoint = streamEndpoints.compute(streamId, (sid, quicStreamEndPoint) ->
                {
                    if (quicStreamEndPoint == null)
                        quicStreamEndPoint = quicConnector.createQuicStreamEndPoint(QuicConnection.this, sid);
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
        return quicheConnection.close();
    }

    public boolean isQuicConnectionClosed()
    {
        return quicheConnection.isConnectionClosed();
    }

    public QuicheConnection getQuicConnection()
    {
        return quicheConnection;
    }

    public boolean hasQuicConnectionTimedOut()
    {
        return System.nanoTime() - registrationTsInNs >= timeoutInNs;
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
}
