package dev.monocle.coordinator;

import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import java.io.IOException;
import java.net.*;
import java.util.function.Function;

/** Shared bounded LAN listener; callers own draining/authenticated message dispatch. */
public final class CrewListener extends Thread implements AutoCloseable {
    private final ServerSocket socket;
    private final Function<String, String> keys;
    private final SwarmConnection[] clients = new SwarmConnection[16];

    public CrewListener(String address, int port, Function<String, String> keys) throws IOException {
        super("Monocle Bots host"); this.keys = keys; setDaemon(true);
        InetAddress bind = InetAddress.getByName(address);
        if (!bind.isLoopbackAddress() && !bind.isSiteLocalAddress()) throw new IOException("Bind to a specific LAN or loopback address");
        socket = new ServerSocket(port, 16, bind);
        start();
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
        for (int i = 0; i < clients.length; i++) if (clients[i] == null || clients[i].socket.isClosed()) {
            clients[i] = new SwarmConnection(socket, keys); clients[i].start(); return;
        }
        socket.close();
    }
    public synchronized SwarmConnection[] connections() { return clients.clone(); }
    public int port() { return socket.getLocalPort(); }
    public boolean listening() { return !socket.isClosed(); }
    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
        for (var c : connections()) if (c != null) c.disconnect();
        interrupt();
    }
}
