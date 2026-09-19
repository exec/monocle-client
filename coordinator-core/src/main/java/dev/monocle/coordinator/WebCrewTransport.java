package dev.monocle.coordinator;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

/** One protocol record per text frame. No game state is accessed by WebSocket callbacks. */
final class WebCrewTransport implements CrewTransport {
    static final String PATH = "/v1/workers";
    private final ArrayBlockingQueue<String> frames = new ArrayBlockingQueue<>(128);
    private final String endpoint;
    private WebSocket socket;
    private WebSocketClient client;
    private volatile boolean stopped;

    static Draft_6455 draft() { return new Draft_6455(List.of(), 64_000); }
    static URI uri(String address) {
        URI uri;
        try { uri = URI.create(address); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid worker URL; use wss://HOST[:PORT]/v1/workers"); }
        if (!List.of("ws", "wss").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null || !PATH.equals(uri.getPath())
            || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Use wss://HOST[:PORT]/v1/workers (no credentials in the URL)");
        if (uri.getScheme().equals("ws") && !List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost()))
            throw new IllegalArgumentException("Plain ws:// is only allowed on loopback; remote workers require wss://");
        return uri;
    }
    static CrewTransport client(String address) {
        URI uri = uri(address);
        WebCrewTransport transport = new WebCrewTransport(address);
        transport.client = new WebSocketClient(uri, draft(), null, 5000) {
            public void onOpen(ServerHandshake handshake) { }
            public void onMessage(String frame) { transport.receive(frame); }
            public void onMessage(java.nio.ByteBuffer bytes) { transport.close(); }
            public void onClose(int code, String reason, boolean remote) { transport.close(); }
            public void onError(Exception error) { transport.close(); }
        };
        transport.client.setDaemon(true);
        transport.socket = transport.client;
        return transport;
    }
    WebCrewTransport(WebSocket socket) { this("WebSocket " + socket.getRemoteSocketAddress()); this.socket = socket; }
    private WebCrewTransport(String endpoint) { this.endpoint = endpoint; }
    void receive(String frame) {
        if (stopped) return;
        if (frame.length() > 16000 || !frames.offer(frame)) close();
    }
    public void open() throws Exception {
        if (stopped) throw new IOException("WebSocket connection closed");
        if (client != null && !client.connectBlocking(5, TimeUnit.SECONDS)) throw new IOException("WebSocket connection failed; check URL, TLS certificate and proxy");
        if (stopped || !socket.isOpen()) throw new IOException("WebSocket connection closed");
    }
    public String read() throws Exception {
        String frame = frames.poll(10, TimeUnit.SECONDS);
        if (stopped) throw new IOException("WebSocket connection closed");
        if (frame == null) throw new IOException("WebSocket receive timeout");
        return frame;
    }
    public void write(String frame) throws Exception {
        // Keep the library's output queue bounded as well as SwarmConnection's queues.
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!stopped && socket.hasBufferedData() && System.nanoTime() < deadline) Thread.sleep(1);
        if (stopped || !socket.isOpen() || socket.hasBufferedData()) throw new IOException("WebSocket send timeout or connection closed");
        socket.send(frame);
    }
    public boolean closed() { return stopped; }
    public void close() {
        if (stopped) return;
        stopped = true; frames.clear(); frames.offer("");
        if (socket != null) socket.closeConnection(1001, "Crew transport closed");
    }
    public String endpoint() { return endpoint; }
}
