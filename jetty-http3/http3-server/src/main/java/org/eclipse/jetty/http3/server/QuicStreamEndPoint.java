package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http3.quic.QuicStream;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicStreamEndPoint extends AbstractEndPoint
{
    protected static final Logger LOG = LoggerFactory.getLogger(QuicStreamEndPoint.class);
    private final QuicEndPointManager quicEndPointManager;
    final AtomicBoolean fillInterested = new AtomicBoolean();
    private final long streamId;

    protected QuicStreamEndPoint(Scheduler scheduler, QuicEndPointManager quicEndPointManager, long streamId)
    {
        super(scheduler);
        this.quicEndPointManager = quicEndPointManager;
        this.streamId = streamId;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return quicEndPointManager.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return quicEndPointManager.getRemoteAddress();
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (quicEndPointManager.isQuicConnectionClosed())
            return -1;

        QuicStream quicStream = quicEndPointManager.quicReadableStream(streamId);
        if (quicStream == null)
            return 0;

        int pos = BufferUtil.flipToFill(buffer);
        int read = quicStream.read(buffer);
        if (quicStream.isReceivedFin())
            shutdownInput();

        BufferUtil.flipToFlush(buffer, pos);
        LOG.debug("filled {} bytes", read);
        return read;
    }

    @Override
    public void onClose(Throwable failure)
    {
        quicEndPointManager.onStreamClosed(streamId);
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        LOG.debug("flush");
        if (quicEndPointManager.isQuicConnectionClosed())
            throw new IOException("connection is closed");

        QuicStream quicStream = quicEndPointManager.quicWritableStream(streamId);
        if (quicStream == null)
            return false;

        long flushed = 0L;
        try
        {
            for (ByteBuffer buffer : buffers)
            {
                flushed += quicStream.write(buffer, false);
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

    @Override
    public Object getTransport()
    {
        return quicEndPointManager.getQuicConnection();
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
