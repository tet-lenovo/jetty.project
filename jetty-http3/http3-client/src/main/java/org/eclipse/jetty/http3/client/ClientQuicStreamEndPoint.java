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

package org.eclipse.jetty.http3.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicStreamEndPoint;
import org.eclipse.jetty.http3.quiche.QuicheStream;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientQuicStreamEndPoint extends QuicStreamEndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicStreamEndPoint.class);

    private final QuicConnection quicConnection;
    //TODO: this atomic duplicates state that should be in FillInterest
    private final AtomicBoolean fillInterested = new AtomicBoolean();
    private final long streamId;

    protected ClientQuicStreamEndPoint(Scheduler scheduler, QuicConnection quicConnection, long streamId)
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

    public void onFillable()
    {
        if (fillInterested.compareAndSet(true, false))
        {
            LOG.debug("Fillable");
            getFillInterest().fillable();
        }
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (quicConnection.isQuicConnectionClosed())
            return -1;

        QuicheStream quicheStream = quicConnection.quicReadableStream(streamId);
        // TODO: there is a race condition here; between fill interest and the return 0
        if (quicheStream == null)
            return 0;

        int pos = BufferUtil.flipToFill(buffer);
        int read = quicheStream.read(buffer);
        if (quicheStream.isReceivedFin())
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

        for (ByteBuffer buffer : buffers)
        {
            int toWrite = buffer.remaining();
            int written = quicConnection.writeToStream(streamId, buffer);
            if (written < toWrite)
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
}
