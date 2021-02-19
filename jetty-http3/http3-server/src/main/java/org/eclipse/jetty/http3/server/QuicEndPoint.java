package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

import org.eclipse.jetty.http3.quic.QuicConnection;
import org.eclipse.jetty.http3.quic.QuicStream;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicEndPoint extends AbstractEndPoint
{
    protected static final Logger LOG = LoggerFactory.getLogger(QuicEndPoint.class);

    private final AtomicBoolean fillInterested = new AtomicBoolean();
    private final QuicConnection quicConnection;
    private final SocketAddress localAddress;
    private volatile long registrationTsInNs;
    private volatile long timeoutInNs;
    private volatile SocketAddress lastPeer;

    private final LongConsumer timeoutSetter = timeoutInMs ->
    {
        registrationTsInNs = System.nanoTime();
        timeoutInNs = TimeUnit.MILLISECONDS.toNanos(timeoutInMs);
        LOG.debug("next timeout is in {}ms", timeoutInMs);
    };

    protected QuicEndPoint(Scheduler scheduler, QuicConnection quicConnection, SocketAddress localAddress, SocketAddress peer)
    {
        super(scheduler);
        this.quicConnection = quicConnection;
        this.localAddress = localAddress;
        this.lastPeer = peer;
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
            if (it.hasNext())
            {
                LOG.debug("a stream is readable");
                if (fillInterested.compareAndSet(true, false))
                {
                    LOG.debug("Fillable");
                    getFillInterest().fillable();
                }
            }
        }
    }

    @Override
    protected void doClose()
    {
        try
        {
            quicConnection.sendClose();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
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
        if (quicConnection.isConnectionClosed())
            return -1;

        int pos = BufferUtil.flipToFill(buffer);
        int read = 0;
        Iterator<QuicStream> it = quicConnection.readableStreamsIterator();
        if (it.hasNext())
        {
            QuicStream stream = it.next();
            int remaining = buffer.remaining();
            byte[] buf = new byte[remaining];
            read = stream.read(buf);
            buffer.put(buf, 0, read);

            if (stream.isReceivedFin())
                shutdownInput();
        }
        BufferUtil.flipToFlush(buffer, pos);
        LOG.debug("filled {} bytes", read);
        return read;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        LOG.debug("flush");
        if (quicConnection.isConnectionClosed())
            throw new IOException("connection is closed");

        Iterator<QuicStream> it = quicConnection.writableStreamsIterator();
        if (it.hasNext())
        {
            QuicStream stream = it.next();

            long flushed = 0L;
            try
            {
                for (ByteBuffer buffer : buffers)
                {
                    flushed += writeToStream(stream, buffer);
                }
                if (LOG.isDebugEnabled())
                    LOG.debug("flushed {} {}", flushed, this);
            }
            catch (IOException e)
            {
                throw new EofException(e);
            }

            if (flushed > 0)
                notIdle();

            for (ByteBuffer b : buffers)
            {
                if (!BufferUtil.isEmpty(b))
                    return false;
            }

            return true;
        }
        else
        {
            return false;
        }
    }

    private long writeToStream(QuicStream stream, ByteBuffer buffer) throws IOException
    {
        byte[] buf = new byte[buffer.remaining()];
        buffer.get(buf);
        int written = stream.write(buf, false);
        int remaining = buf.length - written;
        if (remaining != 0)
            buffer.position(buffer.position() - remaining);
        return written;
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
        LOG.debug("fill interested; currently interested? {}", fillInterested.get());
        fillInterested.set(true);
    }
}
