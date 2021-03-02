package org.eclipse.jetty.http3.server;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;

import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicConnectionManager;
import org.eclipse.jetty.http3.quic.QuicheConfig;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicConnector extends AbstractNetworkConnector
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicConnector.class);

    private QuicheConfig quicheConfig;
    private SSLKeyPair keyPair;
    private QuicConnectionManager quicConnectionManager;

    public QuicConnector(Server server)
    {
        super(server, null, null, null, 0);
    }

    public SSLKeyPair getKeyPair()
    {
        return keyPair;
    }

    public void setKeyPair(SSLKeyPair keyPair)
    {
        this.keyPair = keyPair;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        quicConnectionManager.start();
    }

    @Override
    public void open() throws IOException
    {
        if (quicConnectionManager != null)
            return;

        if (keyPair == null)
            throw new IllegalStateException("Missing key pair");

        File[] files;
        try
        {
            files = keyPair.export(new File(System.getProperty("java.io.tmpdir")));
        }
        catch (Exception e)
        {
            throw new IOException("Error exporting key pair", e);
        }

        quicheConfig = new QuicheConfig();
        quicheConfig.setPrivKeyPemPath(files[0].getPath());
        quicheConfig.setCertChainPemPath(files[1].getPath());
        quicheConfig.setVerifyPeer(false);
        quicheConfig.setMaxIdleTimeout(5000L);
        quicheConfig.setInitialMaxData(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiRemote(10000000L);
        quicheConfig.setInitialMaxStreamDataUni(10000000L);
        quicheConfig.setInitialMaxStreamsBidi(100L);
        quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.RENO);
//        quicConfig.setApplicationProtos(getProtocols().toArray(new String[0]));
        quicheConfig.setApplicationProtos("http/0.9");  // enable HTTP/0.9

        quicConnectionManager = new QuicServerConnectionManager(this, getExecutor(), getScheduler(), getByteBufferPool(), this::createQuicStreamEndPoint, quicheConfig);
        quicConnectionManager.getChannel().bind(bindAddress());
    }

    @Override
    public void close()
    {
        if (quicConnectionManager == null)
            return;

        quicConnectionManager.close();
        quicConnectionManager = null;
        quicheConfig = null;
    }

    public QuicServerStreamEndPoint createQuicStreamEndPoint(QuicConnection quicConnection, long streamId)
    {
        QuicServerStreamEndPoint endPoint = new QuicServerStreamEndPoint(getScheduler(), quicConnection, streamId);
        //TODO: use getConnectionFactory(quicConnection.getNegotiatedProtocol())
        Connection connection = getDefaultConnectionFactory().newConnection(this, endPoint);
        endPoint.setConnection(connection);
        connection.onOpen();
        return endPoint;
    }

    private SocketAddress bindAddress()
    {
        String host = getHost();
        if (host == null)
            host = "0.0.0.0";
        int port = getPort();
        if (port < 0)
            throw new IllegalArgumentException("port cannot be negative: " + port);
        return new InetSocketAddress(host, port);
    }

    @Override
    public Object getTransport()
    {
        return quicConnectionManager == null ? null : quicConnectionManager.getChannel();
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = quicConnectionManager == null ? null : quicConnectionManager.getChannel();
        return channel != null && channel.isOpen();
    }

    @Override
    protected void accept(int acceptorID)
    {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has its own accepting mechanism");
    }
}
