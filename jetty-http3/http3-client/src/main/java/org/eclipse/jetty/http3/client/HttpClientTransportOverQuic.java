package org.eclipse.jetty.http3.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.MultiplexHttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedObject;

import static org.eclipse.jetty.client.http.HttpClientTransportOverHTTP.HTTP11;

@ManagedObject("The QUIC client transport")
public class HttpClientTransportOverQuic extends AbstractHttpClientTransport
{
    private final ClientConnectionFactory connectionFactory = new HttpClientConnectionFactory();
    private final QuicClientConnector connector;

    public HttpClientTransportOverQuic()
    {
        connector = new QuicClientConnector();
        addBean(connector);
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            int maxConnections = httpClient.getMaxConnectionsPerDestination();
            return new MultiplexConnectionPool(destination, maxConnections, destination, httpClient.getMaxRequestsQueuedPerDestination());
        });
    }

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        endPoint.setIdleTimeout(getHttpClient().getIdleTimeout());
        return connectionFactory.newConnection(endPoint, context);
    }

    @Override
    public Origin newOrigin(HttpRequest request)
    {
        return getHttpClient().createOrigin(request, HTTP11);
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new MultiplexHttpDestination(getHttpClient(), origin);
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, destination.getClientConnectionFactory());
        @SuppressWarnings("unchecked")
        Promise<org.eclipse.jetty.client.api.Connection> promise = (Promise<org.eclipse.jetty.client.api.Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, promise::failed));
        connector.connect(address, context, this);
    }
}
