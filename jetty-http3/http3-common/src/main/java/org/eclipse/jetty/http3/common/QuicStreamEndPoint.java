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
