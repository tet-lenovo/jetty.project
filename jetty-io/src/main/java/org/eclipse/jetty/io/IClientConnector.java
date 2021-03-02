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

package org.eclipse.jetty.io;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

public interface IClientConnector
{
    SslContextFactory.Client getSslContextFactory();

    Executor getExecutor();
    void setExecutor(Executor executor);

    Scheduler getScheduler();
    void setScheduler(Scheduler scheduler);

    boolean isConnectBlocking();
    void setConnectBlocking(boolean connectBlocking);

    SocketAddress getBindAddress();
    void setBindAddress(SocketAddress bindAddress);

    ByteBufferPool getByteBufferPool();
    void setByteBufferPool(ByteBufferPool byteBufferPool);

    Duration getIdleTimeout();
    void setIdleTimeout(Duration idleTimeout);

    Duration getConnectTimeout();
    void setConnectTimeout(Duration connectTimeout);
}
