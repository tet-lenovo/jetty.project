package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import org.eclipse.jetty.http3.quic.QuicConnection;
import org.eclipse.jetty.http3.quic.QuicStream;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicEndPoint extends AbstractEndPoint
{
    protected static final Logger LOG = LoggerFactory.getLogger(QuicEndPoint.class);

    final QuicConnection quicConnection;
    private final Map<Long, QuicStreamEndPoint> streamEndpoints = new ConcurrentHashMap<>();

    private final SocketAddress localAddress;
    private volatile long registrationTsInNs;
    private volatile long timeoutInNs;
    private volatile SocketAddress lastPeer;
    private final QuicConnector quicConnector;

    private final LongConsumer timeoutSetter = timeoutInMs ->
    {
        registrationTsInNs = System.nanoTime();
        timeoutInNs = TimeUnit.MILLISECONDS.toNanos(timeoutInMs);
        LOG.debug("next timeout is in {}ms", timeoutInMs);
    };

    protected QuicEndPoint(Scheduler scheduler, QuicConnection quicConnection, SocketAddress localAddress, SocketAddress peer, QuicConnector quicConnector)
    {
        super(scheduler);
        this.quicConnection = quicConnection;
        this.localAddress = localAddress;
        this.lastPeer = peer;
        this.quicConnector = quicConnector;
    }

    public SocketAddress getLastPeer()
    {
        return lastPeer;
    }

    public LongConsumer getTimeoutSetter()
    {
        return timeoutSetter;
    }

    /**
     * @param buffer cipher text
     * @param peer address of the peer who sent the packet
     * @param bufferPool the pool where to release the buffer when done with it
     */
    public void handlePacket(ByteBuffer buffer, SocketAddress peer, ByteBufferPool bufferPool) throws IOException
    {
        LOG.debug("handling packet " + BufferUtil.toDetailString(buffer));
        lastPeer = peer;
        quicConnection.recv(buffer);
        bufferPool.release(buffer);

        if (quicConnection.isConnectionEstablished())
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
                        quicStreamEndPoint = quicConnector.createQuicStreamEndPoint(QuicEndPoint.this, sid);
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

    void onStreamClosed(long streamId)
    {
        streamEndpoints.remove(streamId);
        if (streamEndpoints.isEmpty())
            close();
    }

    public QuicConnection getQuicConnection()
    {
        return quicConnection;
    }

    public boolean hasTimedOut()
    {
        return System.nanoTime() - registrationTsInNs >= timeoutInNs;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return (InetSocketAddress)localAddress;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return (InetSocketAddress)lastPeer;
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        throw new UnsupportedOperationException("cannot fill from raw Quic endpoint");
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        throw new UnsupportedOperationException("cannot flush raw Quic endpoint");
    }

    @Override
    public Object getTransport()
    {
        return quicConnection;
    }

    @Override
    protected void onIncompleteFlush()
    {
        LOG.debug("onIncompleteFlush");
    }

    @Override
    protected void needsFillInterest() throws IOException
    {
        throw new UnsupportedOperationException("cannot set need for fill interest from raw Quic endpoint");
    }
}
