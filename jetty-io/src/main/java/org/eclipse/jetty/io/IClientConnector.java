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
