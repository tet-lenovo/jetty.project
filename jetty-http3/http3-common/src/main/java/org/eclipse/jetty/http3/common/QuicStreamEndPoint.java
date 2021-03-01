package org.eclipse.jetty.http3.common;

import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.util.thread.Scheduler;

public abstract class QuicStreamEndPoint extends AbstractEndPoint
{
    public interface Factory
    {
        QuicStreamEndPoint createQuicStreamEndPoint(QuicConnection quicConnection, long streamId);
    }

    protected QuicStreamEndPoint(Scheduler scheduler)
    {
        super(scheduler);
    }

    public abstract void onFillable();
}
