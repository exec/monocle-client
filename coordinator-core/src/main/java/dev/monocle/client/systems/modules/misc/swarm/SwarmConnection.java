/* Derived from Meteor Client; Copyright (c) Meteor Development. */
package dev.monocle.client.systems.modules.misc.swarm;

import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Function;
import dev.monocle.coordinator.CrewTransport;

/** Shared authenticated, ordered, bounded protocol over LAN TCP or WebSocket. I/O threads never access game state. */
public class SwarmConnection extends Thread {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CrewTransport transport;
    private final String configuredKey;
    private final Function<String, String> keyResolver;
    private final boolean hostSide;
    private final ArrayBlockingQueue<String> outgoing = new ArrayBlockingQueue<>(128);
    private final ArrayBlockingQueue<String> incoming = new ArrayBlockingQueue<>(128);
    private volatile boolean ready;
    private volatile String failure = "";
    private volatile String credentialId = "";
    private volatile SecretKeySpec handoffKey;
    private Thread writer;

    public SwarmConnection(Socket socket, String key, boolean hostSide) {
        this(CrewTransport.socket(socket), key, null, hostSide);
    }
    public SwarmConnection(Socket socket, Function<String, String> keyResolver) {
        this(CrewTransport.socket(socket), null, keyResolver, true);
    }
    public SwarmConnection(CrewTransport transport, String key, boolean hostSide) {
        this(transport, key, null, hostSide);
    }
    public SwarmConnection(CrewTransport transport, Function<String, String> keyResolver) {
        this(transport, null, keyResolver, true);
    }
    private SwarmConnection(CrewTransport transport, String key, Function<String, String> keyResolver, boolean hostSide) {
        super("Monocle Workers reader");
        this.transport = transport;
        this.configuredKey = key;
        this.keyResolver = keyResolver;
        this.hostSide = hostSide;
        setDaemon(true);
    }

    public boolean connected() { return ready && !closed(); }
    public boolean closed() { return transport.closed(); }
    public String failure() { return failure; }
    public String credentialId() { return credentialId; }
    /** Credentials are encrypted even though ordinary LAN status messages are only authenticated. */
    public String sealSecret(String secret) {
        if (!connected() || handoffKey == null) throw new IllegalStateException("Authenticate before transferring credentials");
        if (secret == null || secret.length() > 356) throw new IllegalArgumentException("Credential transfer exceeds its limit");
        byte[] plain = secret.getBytes(StandardCharsets.UTF_8);
        if (plain.length > 356) throw new IllegalArgumentException("Credential transfer exceeds its limit");
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, handoffKey, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) { throw new IllegalStateException("Cannot encrypt crew credential transfer", e); }
    }
    public String openSecret(String sealed) {
        if (!connected() || handoffKey == null) throw new IllegalStateException("Authenticate before receiving credentials");
        if (sealed == null || sealed.length() > 512) throw new IllegalArgumentException("Invalid crew credential transfer");
        try {
            byte[] encrypted = Base64.getDecoder().decode(sealed);
            if (encrypted.length < 28) throw new IllegalArgumentException("Invalid crew credential transfer");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, handoffKey, new GCMParameterSpec(128, encrypted, 0, 12));
            return new String(cipher.doFinal(encrypted, 12, encrypted.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid crew credential transfer", e); }
    }
    public String poll() { return incoming.poll(); }
    public boolean send(String message) {
        if (!connected()) return false;
        if (message.length() > 16000 || !outgoing.offer(message)) {
            failure = "Workers message queue exceeded its limit";
            disconnect();
            return false;
        }
        return true;
    }

    static String mac(String key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
    static boolean authentic(String key, String text, String signature) throws Exception {
        return MessageDigest.isEqual(mac(key, text).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }
    public static String credentialSelector(String key) {
        if (key == null || key.length() < 24) throw new IllegalArgumentException("Workers keys must contain at least 24 characters");
        try { return mac(key, "monocle-bots-crew"); }
        catch (Exception e) { throw new IllegalStateException("Cannot generate Workers credential selector", e); }
    }

    @Override public void run() {
        try {
            if (keyResolver == null && (configuredKey == null || configuredKey.length() < 24))
                throw new IOException("Set the same Workers key (24+ characters) on both clients");
            transport.open();
            String nonce = UUID.randomUUID().toString();
            String localSelector = hostSide ? "" : credentialSelector(configuredKey);
            transport.write("monocle-crew-6:" + nonce + (hostSide ? "" : ":" + localSelector));
            transport.flush();
            String peer = transport.read();
            if (!peer.matches("monocle-crew-6:[0-9a-f-]{36}" + (hostSide ? ":[0-9a-f]{64}" : "")))
                throw new IOException("Incompatible Workers protocol; install the same build on every account");
            String peerNonce = peer.substring("monocle-crew-6:".length(), "monocle-crew-6:".length() + 36);
            String selected = hostSide ? peer.substring(peer.length() - 64) : localSelector;
            final String key = hostSide && keyResolver != null ? keyResolver.apply(selected) : configuredKey;
            if (key == null || key.length() < 24 || !MessageDigest.isEqual(credentialSelector(key).getBytes(StandardCharsets.US_ASCII), selected.getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("Workers key mismatch or unknown crew");
            String context = selected + ":" + (hostSide ? nonce + ":" + peerNonce : peerNonce + ":" + nonce);
            String sendDirection = hostSide ? "host" : "worker", receiveDirection = hostSide ? "worker" : "host";
            transport.write(mac(key, context + sendDirection));
            transport.flush();
            if (!authentic(key, context + receiveDirection, transport.read())) throw new IOException("Workers key mismatch");
            handoffKey = new SecretKeySpec(HexFormat.of().parseHex(mac(key, context + "credential-handoff")), "AES");
            credentialId = selected;
            ready = true;
            writer = new Thread(() -> {
                long sequence = 0;
                try {
                    while (!closed()) {
                        String message = outgoing.take();
                        transport.write(message);
                        transport.write(mac(key, context + sendDirection + sequence++ + ":" + message));
                        transport.flush();
                    }
                } catch (Exception e) { disconnect(); }
            }, "Monocle Workers writer");
            writer.setDaemon(true);
            writer.start();
            long sequence = 0;
            while (!closed()) {
                String message = transport.read();
                if (message.length() > 16000 || !authentic(key, context + receiveDirection + sequence++ + ":" + message, transport.read()))
                    throw new IOException("Invalid Workers message");
                if (!incoming.offer(message)) throw new IOException("Workers receive queue full");
            }
        } catch (Exception e) {
            // Socket.connect may close the socket itself on failure; only explicit cancellation suppresses errors.
            if (!isInterrupted()) failure = e.getMessage() == null ? "Workers connection lost" : e.getMessage();
        } finally { disconnect(); }
    }
    public void disconnect() {
        ready = false;
        handoffKey = null;
        transport.close();
        if (writer != null) writer.interrupt();
        interrupt();
    }
    public String getConnection() { return transport.endpoint(); }
}
