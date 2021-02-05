package org.eclipse.jetty.http3.quic;

import java.io.IOException;

import org.eclipse.jetty.http3.quic.quiche.LibQuiche;
import org.eclipse.jetty.http3.quic.quiche.bool_pointer;
import org.eclipse.jetty.http3.quic.quiche.size_t;
import org.eclipse.jetty.http3.quic.quiche.ssize_t;
import org.eclipse.jetty.http3.quic.quiche.uint64_t;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http3.quic.quiche.LibQuiche.INSTANCE;

public class QuicStream
{
    private static final Logger LOGGER = LoggerFactory.getLogger(QuicStream.class);

    private final LibQuiche.quiche_conn quicheConn;
    private final long streamId;
    private boolean receivedFin;

    QuicStream(LibQuiche.quiche_conn quicheConn, long streamId)
    {
        this.quicheConn = quicheConn;
        this.streamId = streamId;
    }

    public long getStreamId()
    {
        return streamId;
    }

    public int read(byte[] buffer) throws IOException
    {
        bool_pointer fin = new bool_pointer();
        int recv = INSTANCE.quiche_conn_stream_recv(quicheConn, new uint64_t(streamId), buffer, new size_t(buffer.length), fin).intValue();
        LOGGER.debug("Received {} byte(s) from stream {} (fin? {})", recv, streamId, fin.getValue());
        receivedFin |= fin.getValue();
        if (recv == LibQuiche.quiche_error.QUICHE_ERR_DONE)
            return 0;
        if (recv < 0)
            throw new IOException("Error reading from stream " + streamId + ": " + LibQuiche.quiche_error.errToString(recv));
        return recv;
    }

    public int write(byte[] buffer, boolean fin) throws IOException
    {
        int sent = INSTANCE.quiche_conn_stream_send(quicheConn, new uint64_t(streamId), buffer, new size_t(buffer.length), fin).intValue();
        LOGGER.debug("Sent {} byte(s) to stream {} (fin? {})", sent, streamId, fin);
        if (sent == LibQuiche.quiche_error.QUICHE_ERR_DONE)
            return 0;
        if (sent < 0)
            throw new IOException("Error writing to stream " + streamId + ": " + LibQuiche.quiche_error.errToString(sent));
        return sent;
    }

    public boolean isReceivedFin()
    {
        return receivedFin;
    }
}
