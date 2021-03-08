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
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.FillInterest;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicStreamEndPoint extends AbstractEndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicStreamEndPoint.class);

    private final QuicConnection quicConnection;
    //TODO: this atomic duplicates state that should be in FillInterest
    private final AtomicBoolean fillInterested = new AtomicBoolean();
    private final long streamId;
    private final Flusher flusher;

    public QuicStreamEndPoint(Scheduler scheduler, QuicConnection quicConnection, long streamId, Flusher flusher)
    {
        super(scheduler);
        this.quicConnection = quicConnection;
        this.streamId = streamId;
        this.flusher = flusher;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return quicConnection.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return quicConnection.getRemoteAddress();
    }

    public FillInterest onFillable()
    {
        if (fillInterested.compareAndSet(true, false))
        {
            LOG.debug("onFillable interested stream {}", streamId);
            return getFillInterest();
        }
        else
        {
            LOG.debug("onFillable uninterested stream {}", streamId);
            return null;
        }
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (quicConnection.isQuicConnectionClosed())
            return -1;

        int pos = BufferUtil.flipToFill(buffer);
        int read = quicConnection.readFromStream(streamId, buffer);
        if (quicConnection.isFinished(streamId))
            shutdownInput();

        BufferUtil.flipToFlush(buffer, pos);
        LOG.debug("filled {} bytes", read);
        return read;
    }

    @Override
    public void onClose(Throwable failure)
    {
        quicConnection.onStreamClosed(streamId);
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        LOG.debug("flush");
        if (quicConnection.isQuicConnectionClosed())
            throw new IOException("connection is closed");

        long flushed = 0L;
        try
        {
            for (ByteBuffer buffer : buffers)
            {
                int written = quicConnection.writeToStream(streamId, buffer);
                flusher.flush(quicConnection);
                flushed += written;
                if (buffer.remaining() != 0)
                    break;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("flushed {} byte(s) - {}", flushed, this);
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
        return quicConnection;
    }

    @Override
    protected void onIncompleteFlush()
    {
        LOG.warn("unimplemented onIncompleteFlush");
    }

    @Override
    protected void needsFillInterest() throws IOException
    {
        LOG.debug("fill interested; currently interested? {}", fillInterested.get());
        fillInterested.set(true);
    }

    public interface Factory
    {
        QuicStreamEndPoint createQuicStreamEndPoint(QuicConnection quicConnection, long streamId, Flusher flusher);
    }

    public interface Flusher
    {
        void flush(QuicConnection quicConnection);
    }
}
