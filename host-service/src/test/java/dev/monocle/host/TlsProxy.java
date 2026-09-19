package dev.monocle.host;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

/** Test-only TLS terminator: exercises production clients through a real encrypted proxy. */
final class TlsProxy implements AutoCloseable {
    private static SSLContext context;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    TlsProxy(int backend) throws Exception {
        server = context().getServerSocketFactory().createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread.startVirtualThread(() -> {
            while (!server.isClosed()) try {
                Socket front = server.accept(); sockets.add(front);
                Thread.startVirtualThread(() -> {
                    try (front; Socket back = new Socket("127.0.0.1", backend)) {
                        sockets.add(back);
                        Thread.startVirtualThread(() -> copy(back, front)); copy(front, back);
                        sockets.remove(back);
                    } catch (IOException ignored) { }
                    finally { sockets.remove(front); }
                });
            } catch (IOException ignored) { break; }
        });
    }
    int port() { return server.getLocalPort(); }
    static synchronized SSLContext context() throws Exception {
        if (context != null) return context;
        Path keys = Files.createTempDirectory("monocle-tls-check-").resolve("test.p12");
        Process generation = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "localhost", "-keyalg", "EC", "-storetype", "PKCS12", "-keystore", keys.toString(),
            "-storepass", "local-test-only", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-noprompt")
            .redirectErrorStream(true).start();
        if (!generation.waitFor(15, TimeUnit.SECONDS)) { generation.destroyForcibly(); throw new IOException("Test certificate generation timed out"); }
        if (generation.exitValue() != 0) throw new IOException("Test certificate generation failed: " + new String(generation.getInputStream().readAllBytes()));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keys)) { store.load(input, "local-test-only".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(store, "local-test-only".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(store);
        context = SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        return context;
    }
    private static void copy(Socket from, Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); }
        catch (IOException ignored) { }
        finally { try { from.close(); to.close(); } catch (IOException ignored) { } }
    }
    public void close() throws IOException { server.close(); for (Socket socket : sockets) socket.close(); }
}
