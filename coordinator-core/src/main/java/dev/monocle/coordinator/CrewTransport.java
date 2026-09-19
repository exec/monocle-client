package dev.monocle.coordinator;

import java.io.*;
import java.net.*;

/** Framed I/O only; authentication, queues and execution receipts belong to SwarmConnection. */
public interface CrewTransport {
    void open() throws Exception;
    String read() throws Exception;
    void write(String frame) throws Exception;
    default void flush() throws Exception { }
    boolean closed();
    void close();
    String endpoint();

    static CrewTransport worker(String address, int port) {
        return address.contains("://") ? WebCrewTransport.client(address) : socket(new Socket(), address, port);
    }
    static CrewTransport socket(Socket socket) { return socket(socket, null, 0); }
    private static CrewTransport socket(Socket socket, String address, int port) {
        return new CrewTransport() {
            private DataInputStream in;
            private DataOutputStream out;
            public void open() throws IOException {
                if (address != null) {
                    InetAddress host = InetAddress.getByName(address.equalsIgnoreCase("localhost") ? "127.0.0.1" : address);
                    if (!host.isLoopbackAddress() && !host.isSiteLocalAddress()) throw new IOException("Use a LAN host or a secure wss:// worker URL");
                    socket.connect(new InetSocketAddress(host, port), 3000);
                }
                socket.setSoTimeout(10000); socket.setTcpNoDelay(true);
                in = new DataInputStream(socket.getInputStream()); out = new DataOutputStream(socket.getOutputStream());
            }
            public String read() throws IOException { return in.readUTF(); }
            public void write(String frame) throws IOException { out.writeUTF(frame); }
            public void flush() throws IOException { out.flush(); }
            public boolean closed() { return socket.isClosed(); }
            public void close() { try { socket.close(); } catch (IOException ignored) { } }
            public String endpoint() { return address == null ? String.valueOf(socket.getRemoteSocketAddress()) : address + ":" + port; }
        };
    }
}
