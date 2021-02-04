package org.eclipse.jetty.http3.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import org.eclipse.jetty.http3.quic.QuicConnection;
import org.eclipse.jetty.http3.quic.QuicStream;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicEndPoint extends AbstractEndPoint
{
    protected static final Logger LOG = LoggerFactory.getLogger(QuicEndPoint.class);

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

    protected QuicEndPoint(Scheduler scheduler, QuicConnection quicConnection, SocketAddress localAddress)
    {
        super(scheduler);
        this.quicConnection = quicConnection;
        this.localAddress = localAddress;
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
        lastPeer = peer;
        quicConnection.recv(buffer);
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

        Iterator<QuicStream> it = quicConnection.readableStreamsIterator();
        if (it.hasNext())
        {
            QuicStream stream = it.next();
            int remaining = buffer.remaining();
            byte[] buf = new byte[remaining];
            int read = stream.read(buf);
            buffer.put(buf, 0, read);
            ((Closeable)stream).close();
            return read;
        }
        return 0;
    }

    @Override
    public boolean flush(ByteBuffer... buffer) throws IOException
    {
        return false;
    }

    @Override
    public Object getTransport()
    {
        return quicConnection;
    }

    @Override
    protected void onIncompleteFlush()
    {

    }

    @Override
    protected void needsFillInterest() throws IOException
    {

    }
}
