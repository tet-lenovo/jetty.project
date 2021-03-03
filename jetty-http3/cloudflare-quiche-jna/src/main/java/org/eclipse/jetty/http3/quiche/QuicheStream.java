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

package org.eclipse.jetty.http3.quiche;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.http3.quiche.ffi.bool_pointer;
import org.eclipse.jetty.http3.quiche.ffi.size_t;
import org.eclipse.jetty.http3.quiche.ffi.uint64_t;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http3.quiche.ffi.LibQuiche.INSTANCE;

public class QuicheStream
{
    private static final Logger LOGGER = LoggerFactory.getLogger(QuicheStream.class);

    static
    {
        LibQuiche.Logging.enable();
    }

    private final LibQuiche.quiche_conn quicheConn;
    private final long streamId;
    private boolean receivedFin;

    QuicheStream(LibQuiche.quiche_conn quicheConn, long streamId)
    {
        this.quicheConn = quicheConn;
        this.streamId = streamId;
    }

    public long getStreamId()
    {
        return streamId;
    }

    public int read(ByteBuffer buffer) throws IOException
    {
        bool_pointer fin = new bool_pointer();
        int recv = INSTANCE.quiche_conn_stream_recv(quicheConn, new uint64_t(streamId), buffer, new size_t(buffer.remaining()), fin).intValue();
        LOGGER.debug("Received {} byte(s) from stream {} (fin? {})", recv, streamId, fin.getValue());
        receivedFin |= fin.getValue();
        if (recv == LibQuiche.quiche_error.QUICHE_ERR_DONE)
            return 0;
        if (recv < 0)
            throw new IOException("Error reading from stream " + streamId + ": " + LibQuiche.quiche_error.errToString(recv));
        buffer.position(buffer.position() + recv);
        return recv;
    }

    public int write(ByteBuffer buffer, boolean fin) throws IOException
    {
        int sent = INSTANCE.quiche_conn_stream_send(quicheConn, new uint64_t(streamId), buffer, new size_t(buffer.remaining()), fin).intValue();
        LOGGER.debug("Sent {} byte(s) to stream {} (fin? {})", sent, streamId, fin);
        if (sent == LibQuiche.quiche_error.QUICHE_ERR_DONE)
            return 0;
        if (sent < 0)
            throw new IOException("Error writing to stream " + streamId + ": " + LibQuiche.quiche_error.errToString(sent));
        buffer.position(buffer.position() + sent);
        return sent;
    }

    public boolean isFinished()
    {
        return INSTANCE.quiche_conn_stream_finished(quicheConn, new uint64_t(streamId));
    }

    public boolean isReceivedFin()
    {
        return receivedFin;
    }
}
