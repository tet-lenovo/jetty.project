package org.eclipse.jetty.http3.server;

import java.io.Closeable;
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
    private final ByteBufferPool bufferPool;
    private volatile long registrationTsInNs;
    private volatile long timeoutInNs;
    private volatile SocketAddress lastPeer;

    private final LongConsumer timeoutSetter = timeoutInMs ->
    {
        registrationTsInNs = System.nanoTime();
        timeoutInNs = TimeUnit.MILLISECONDS.toNanos(timeoutInMs);
        LOG.debug("next timeout is in {}ms", timeoutInMs);
    };

    protected QuicEndPoint(Scheduler scheduler, QuicConnection quicConnection, SocketAddress localAddress, ByteBufferPool bufferPool)
    {
        super(scheduler);
        this.quicConnection = quicConnection;
        this.localAddress = localAddress;
        this.bufferPool = bufferPool;
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
     */
    public void handlePacket(ByteBuffer buffer, SocketAddress peer) throws IOException
    {
        System.out.println("handling packet " + BufferUtil.toDetailString(buffer));
        lastPeer = peer;
        quicConnection.recv(buffer);
        bufferPool.release(buffer);

        if (quicConnection.isConnectionEstablished())
        {
            Iterator<QuicStream> it = quicConnection.readableStreamsIterator();
            if (it.hasNext())
            {
                System.out.println("a stream is readable");
                if (fillInterested.compareAndSet(true, false))
                {
                    System.out.println("Fillable");
                    getFillInterest().fillable();
                }
            }
            ((Closeable)it).close();
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
        System.out.println("fill");
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
        }
        ((Closeable)it).close();
        BufferUtil.flipToFlush(buffer, pos);
        return read;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        System.out.println("flush");
        boolean written = false;
        Iterator<QuicStream> it = quicConnection.writableStreamsIterator();
        if (it.hasNext())
        {
            QuicStream stream = it.next();
            for (ByteBuffer buffer : buffers)
            {
                byte[] buf = new byte[buffer.remaining()];
                buffer.get(buf);
                stream.write(buf, false);
                written = true;
            }
        }
        ((Closeable)it).close();
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
        System.out.println("onIncompleteFlush");
    }

    @Override
    protected void needsFillInterest() throws IOException
    {
        System.out.println("fill interested");
        fillInterested.set(true);
    }
}
