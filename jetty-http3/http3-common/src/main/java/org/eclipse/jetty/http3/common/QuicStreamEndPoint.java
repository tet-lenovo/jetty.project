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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Invocable;
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

    public QuicStreamEndPoint(Scheduler scheduler, QuicConnection quicConnection, long streamId)
    {
        super(scheduler);
        this.quicConnection = quicConnection;
        this.streamId = streamId;
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

    public Task onSelected(boolean fillable, boolean flushable)
    {
        LOG.debug("onSelected fillable {} flushable {}", fillable, flushable);
        if (fillable)
            fillable = fillInterested.compareAndSet(true, false);

        LOG.debug("onSelected after fillInterested check fillable {}", fillable);

        // return task to complete the job
        Task task = fillable
            ? (flushable
            ? _runCompleteWriteFillable
            : _runFillable)
            : (flushable
            ? _runCompleteWrite
            : null);

        if (LOG.isDebugEnabled())
            LOG.debug("onSelected task {}", task);
        return task;
    }

    private final Task _runCompleteWriteFillable = new Task("runCompleteWriteFillable")
    {
        @Override
        public InvocationType getInvocationType()
        {
            InvocationType fillT = getFillInterest().getCallbackInvocationType();
            InvocationType flushT = getWriteFlusher().getCallbackInvocationType();
            if (fillT == flushT)
                return fillT;

            if (fillT == InvocationType.EITHER && flushT == InvocationType.NON_BLOCKING)
                return InvocationType.EITHER;

            if (fillT == InvocationType.NON_BLOCKING && flushT == InvocationType.EITHER)
                return InvocationType.EITHER;

            return InvocationType.BLOCKING;
        }

        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
            getFillInterest().fillable();
        }
    };

    private final Task _runFillable = new Task("runFillable")
    {
        @Override
        public InvocationType getInvocationType()
        {
            return getFillInterest().getCallbackInvocationType();
        }

        @Override
        public void run()
        {
            getFillInterest().fillable();
        }
    };

    private final Task _runCompleteWrite = new Task("runCompleteWrite")
    {
        @Override
        public InvocationType getInvocationType()
        {
            return getWriteFlusher().getCallbackInvocationType();
        }

        @Override
        public void run()
        {
            getWriteFlusher().completeWrite();
        }
    };

    public abstract static class Task implements Invocable, Runnable
    {
        private final String op;

        public Task(String op)
        {
            this.op = op;
        }

        @Override
        public abstract InvocationType getInvocationType();

        @Override
        public String toString()
        {
            return op;
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
    protected void doShutdownInput()
    {
        try
        {
            quicConnection.shutdownInput(streamId);
        }
        catch (IOException e)
        {
            LOG.warn("Error shutting down input of stream {}", streamId, e);
        }
    }

    @Override
    protected void doShutdownOutput()
    {
        try
        {
            quicConnection.shutdownOutput(streamId);
        }
        catch (IOException e)
        {
            LOG.warn("Error shutting down output of stream {}", streamId, e);
        }
    }

    @Override
    public void onClose(Throwable failure)
    {
        super.onClose(failure);
        shutdownOutput();
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
                int written = quicConnection.writeToStream(streamId, buffer); // TODO: make sure that the only reason why written != buffer.remaining() is because Quiche is congested. That is currently assumed.
                flushed += written;
                if (buffer.remaining() != 0)
                {
                    LOG.debug("unconsumed buffer, {} remaining", buffer.remaining());
                    break;
                }
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
        quicConnection.flush();
    }

    @Override
    protected void needsFillInterest() throws IOException
    {
        LOG.debug("fill interested; currently interested? {}", fillInterested.get());
        fillInterested.set(true);
    }

    public interface Factory
    {
        QuicStreamEndPoint createQuicStreamEndPoint(QuicConnection quicConnection, long streamId);
    }
}
