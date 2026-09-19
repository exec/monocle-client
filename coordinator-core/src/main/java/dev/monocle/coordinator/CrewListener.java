package dev.monocle.coordinator;

import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import java.io.IOException;
import java.net.*;
import java.util.function.Function;
import java.util.List;
import java.util.concurrent.*;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.*;
import org.java_websocket.server.WebSocketServer;

/** Shared bounded LAN TCP and optional loopback WebSocket ingress; callers own message dispatch. */
public final class CrewListener extends Thread implements AutoCloseable {
    private final ServerSocket socket;
    private final Function<String, String> keys;
    private final SwarmConnection[] clients = new SwarmConnection[16];
    private WebSocketServer web;

    public CrewListener(String address, int port, Function<String, String> keys) throws IOException {
        this(address, port, keys, -1);
    }
    /** Web ingress is loopback-only: TLS is terminated by the operator's reverse proxy. */
    public CrewListener(String address, int port, Function<String, String> keys, int webPort) throws IOException {
        super("Monocle Workers host"); this.keys = keys; setDaemon(true);
        InetAddress bind = InetAddress.getByName(address);
        if (!bind.isLoopbackAddress() && !bind.isSiteLocalAddress()) throw new IOException("Bind to a specific LAN or loopback address");
        socket = new ServerSocket(port, 16, bind);
        try { if (webPort >= 0) startWeb(webPort); }
        catch (IOException | RuntimeException e) { close(); throw e; }
        start();
    }
    private void startWeb(int port) throws IOException {
        CountDownLatch started = new CountDownLatch(1);
        IOException[] failure = new IOException[1];
        web = new WebSocketServer(new InetSocketAddress("127.0.0.1", port), 1, List.of(WebCrewTransport.draft())) {
            public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket c, Draft d, ClientHandshake h) throws InvalidDataException {
                if (!h.getResourceDescriptor().equals(WebCrewTransport.PATH) || h.hasFieldValue("Origin"))
                    throw new InvalidDataException(1008, "Native worker endpoint required");
                return super.onWebsocketHandshakeReceivedAsServer(c, d, h);
            }
            protected boolean onConnect(java.nio.channels.SelectionKey key) { return getConnections().size() < clients.length; }
            public void onOpen(WebSocket c, ClientHandshake h) {
                WebCrewTransport transport = new WebCrewTransport(c); c.setAttachment(transport);
                assign(transport);
            }
            public void onMessage(WebSocket c, String frame) { WebCrewTransport t = c.getAttachment(); if (t != null) t.receive(frame); }
            public void onMessage(WebSocket c, java.nio.ByteBuffer frame) { c.closeConnection(1008, "Text records required"); }
            public void onClose(WebSocket c, int code, String reason, boolean remote) { WebCrewTransport t = c.getAttachment(); if (t != null) t.close(); }
            public void onError(WebSocket c, Exception e) {
                if (c != null) c.closeConnection(1011, "Transport error");
                else { failure[0] = new IOException("Cannot start worker WebSocket listener", e); started.countDown(); }
            }
            public void onStart() { started.countDown(); }
        };
        web.setDaemon(true); web.setConnectionLostTimeout(10); web.start();
        try {
            if (!started.await(5, TimeUnit.SECONDS)) throw new IOException("Worker WebSocket listener startup timed out");
            if (failure[0] != null) throw failure[0];
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Listener startup interrupted", e); }
    }
    @Override public void run() {
        try {
            while (!socket.isClosed()) {
                Socket accepted = socket.accept();
                if (!accepted.getInetAddress().isLoopbackAddress() && !accepted.getInetAddress().isSiteLocalAddress()) accepted.close();
                else assign(accepted);
            }
        } catch (IOException ignored) { close(); }
    }
    public synchronized void assign(Socket socket) throws IOException {
        if (!listening()) { socket.close(); return; }
        assign(CrewTransport.socket(socket));
    }
    private synchronized void assign(CrewTransport transport) {
        if (!listening()) { transport.close(); return; }
        for (int i = 0; i < clients.length; i++) if (clients[i] == null || clients[i].closed()) {
            clients[i] = new SwarmConnection(transport, keys); clients[i].start(); return;
        }
        transport.close();
    }
    public synchronized SwarmConnection[] connections() { return clients.clone(); }
    public int port() { return socket.getLocalPort(); }
    public int webPort() { return web == null ? -1 : web.getPort(); }
    public boolean listening() { return !socket.isClosed(); }
    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
        if (web != null) try { web.stop(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (var c : connections()) if (c != null) c.disconnect();
        interrupt();
    }
}
