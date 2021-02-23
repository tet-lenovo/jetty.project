package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
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
    private final QuicEndPoint quicEndPoint;
    final AtomicBoolean fillInterested = new AtomicBoolean();
    private final long streamId;

    protected QuicStreamEndPoint(Scheduler scheduler, QuicEndPoint quicEndPoint, long streamId)
    {
        super(scheduler);
        this.quicEndPoint = quicEndPoint;
        this.streamId = streamId;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return quicEndPoint.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return quicEndPoint.getRemoteAddress();
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (quicEndPoint.quicConnection.isConnectionClosed())
            return -1;

        int pos = BufferUtil.flipToFill(buffer);
        int read = 0;
        Iterator<QuicStream> it = quicEndPoint.quicConnection.readableStreamsIterator();
        while (it.hasNext())
        {
            QuicStream stream = it.next();
            if (stream.getStreamId() != streamId)
                continue;
            read = stream.read(buffer);
            if (stream.isReceivedFin())
                shutdownInput();
        }
        BufferUtil.flipToFlush(buffer, pos);
        LOG.debug("filled {} bytes", read);
        return read;
    }

    @Override
    public void onClose(Throwable failure)
    {
        quicEndPoint.onStreamClosed(streamId);
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        LOG.debug("flush");
        if (quicEndPoint.quicConnection.isConnectionClosed())
            throw new IOException("connection is closed");

        Iterator<QuicStream> it = quicEndPoint.quicConnection.writableStreamsIterator();
        while (it.hasNext())
        {
            QuicStream stream = it.next();
            if (stream.getStreamId() != streamId)
                continue;

            long flushed = 0L;
            try
            {
                for (ByteBuffer buffer : buffers)
                {
                    flushed += stream.write(buffer, false);
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
        return false;
    }

    @Override
    public Object getTransport()
    {
        return quicEndPoint.getTransport();
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
