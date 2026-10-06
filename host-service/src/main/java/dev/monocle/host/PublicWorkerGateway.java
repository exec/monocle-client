package dev.monocle.host;

import com.google.gson.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.*;
import org.java_websocket.server.WebSocketServer;

/** Draft v1 public worker socket; portable actions use the host's existing durable job queue. */
final class PublicWorkerGateway extends WebSocketServer implements AutoCloseable {
    static final String PATH = "/v1/interop/workers", VERSION = "rwp/1-draft", LEGACY_VERSION = "workers.monocle.dev/v1";
    private static final int MAX_FRAME = 16_000, MAX_PENDING_FRAMES = 32, MAX_INBOUND_FRAMES_PER_SECOND = 64;
    private final Map<UUID, String> credentials;
    private final Map<UUID, WebSocket> active = new ConcurrentHashMap<>();
    private final HostService host;
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile IOException startupFailure;

    static final class Session {
        final UUID worker;
        volatile boolean accepted;
        String version;
        long nextSequence;
        long lastWorkerSequence;
        long inboundWindow;
        int inboundFrames;
        Session(UUID worker) { this.worker = worker; }
        synchronized boolean allowFrame(long now) {
            if(inboundFrames==0 || now-inboundWindow>=TimeUnit.SECONDS.toNanos(1)){inboundWindow=now;inboundFrames=0;}
            return ++inboundFrames<=MAX_INBOUND_FRAMES_PER_SECOND;
        }
    }

    PublicWorkerGateway(int port, Map<UUID, String> credentials) throws IOException { this(port,credentials,null); }
    PublicWorkerGateway(int port, Map<UUID, String> credentials, HostService host) throws IOException {
        super(new InetSocketAddress("127.0.0.1", port), 1, List.of(new Draft_6455(List.of(), 64_000)));
        if (credentials.isEmpty() || credentials.size() > 16 || new HashSet<>(credentials.values()).size() != credentials.size()
            || credentials.values().stream().anyMatch(key -> key.length() < 24 || key.length() > 128))
            throw new IllegalArgumentException("Configure 1–16 distinct public-worker secrets of 24–128 characters");
        this.credentials = Map.copyOf(credentials);
        this.host = host;
        setDaemon(true); setConnectionLostTimeout(10); start();
        try {
            if (!started.await(5, TimeUnit.SECONDS)) throw new IOException("Public worker listener startup timed out");
            if (startupFailure != null) throw startupFailure;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Public worker listener startup interrupted", e); }
        catch (IOException e) { close(); throw e; }
        if(host!=null)host.publicWorkerGateway(this);
    }

    @Override public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket socket, Draft draft, ClientHandshake request) throws InvalidDataException {
        if (!PATH.equals(request.getResourceDescriptor()) || request.hasFieldValue("Origin"))
            throw new InvalidDataException(1008, "Public worker endpoint required");
        String header = request.getFieldValue("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() > 135) throw new InvalidDataException(1008, "Worker credential required");
        byte[] supplied = header.substring(7).getBytes(StandardCharsets.UTF_8);
        UUID worker = null;
        for (var entry : credentials.entrySet()) if (MessageDigest.isEqual(supplied, entry.getValue().getBytes(StandardCharsets.UTF_8))) worker = entry.getKey();
        if (worker == null) throw new InvalidDataException(1008, "Worker credential rejected");
        socket.setAttachment(new Session(worker));
        return super.onWebsocketHandshakeReceivedAsServer(socket, draft, request);
    }

    @Override protected boolean onConnect(java.nio.channels.SelectionKey key) { return getConnections().size() < 16; }
    @Override public void onOpen(WebSocket socket, ClientHandshake handshake) {
        Session session = socket.getAttachment();
        if (active.putIfAbsent(session.worker, socket) != null) socket.closeConnection(1008, "Worker already connected");
        else CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() -> {
            if (!session.accepted && socket.isOpen()) socket.close(1008, "session.hello timed out");
        });
    }
    @Override public void onMessage(WebSocket socket, String frame) {
        Session session = socket.getAttachment();
        if (!session.allowFrame(System.nanoTime())) { socket.closeConnection(1008, "Message rate exceeded"); return; }
        if (frame.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME) { socket.closeConnection(1009, "Frame too large"); return; }
        UUID correlation = UUID.randomUUID();
        try {
            JsonObject message = JsonParser.parseString(frame).getAsJsonObject();
            correlation = uuid(message,"correlationId");
            String version=string(message,"apiVersion");
            if (!Set.of(VERSION,LEGACY_VERSION).contains(version) || session.accepted && !version.equals(session.version))
                throw new IllegalArgumentException("Unsupported API major version");
            UUID messageId=uuid(message,"messageId");
            Instant.parse(string(message,"sentAt"));
            if (!message.has("payload") || !message.get("payload").isJsonObject()) throw new IllegalArgumentException("Missing payload");
            JsonObject payload = message.getAsJsonObject("payload");
            if (!session.accepted) {
                if (!"session.hello".equals(string(message,"type")) || number(message,"sequence")!=0)
                    throw new IllegalArgumentException("Unsupported v1 session message");
                session.version=version;
                hello(session,version,payload);
                session.accepted = true;
                JsonObject accepted = new JsonObject(); accepted.addProperty("sessionId", UUID.randomUUID().toString());
                accepted.addProperty("version", session.version); accepted.addProperty("maxFrameBytes", MAX_FRAME);
                accepted.addProperty("heartbeatSeconds", 10);
                JsonObject implementation=new JsonObject();implementation.addProperty("id","dev.monocle.host");
                String build=PublicWorkerGateway.class.getPackage().getImplementationVersion();
                implementation.addProperty("version",build==null?"dev":build);accepted.add("implementation",implementation);
                JsonArray supported=new JsonArray();if(host!=null)for(String id:List.of("workers.wait.v1","workers.travel.v1")){JsonObject capability=new JsonObject();capability.addProperty("id",id);supported.add(capability);}
                accepted.add("capabilities",supported);accepted.addProperty("executionEnabled", host!=null);
                accepted.addProperty("extensionRouting",host!=null);
                send(socket, session, "session.accepted", correlation, accepted);
                return;
            }
            if (!session.worker.equals(uuid(message,"workerId")) || number(message,"sequence")!=session.lastWorkerSequence+1)
                throw new IllegalArgumentException("Worker identity or sequence mismatch");
            session.lastWorkerSequence++;
            if (host==null) throw new IllegalArgumentException("Public execution is not available");
            String type=string(message,"type");
            JsonObject result=switch(type){
                case "worker.observation" -> { host.publicWorkerObservation(session.worker,payload);yield null; }
                case "execution.progress" -> { host.publicWorkerProgress(session.worker,payload);yield null; }
                case "state.reconcile" -> host.publicWorkerReconcile(session.worker,payload);
                case "execution.accepted", "execution.rejected", "execution.started", "execution.completed", "execution.failed", "execution.cancelled" -> {
                    boolean duplicate=host.publicWorkerReport(session.worker,messageId,type,payload);
                    JsonObject ack=new JsonObject();ack.addProperty("messageId",messageId.toString());
                    ack.addProperty("result",duplicate?"duplicate":"accepted");
                    send(socket,session,"message.ack",correlation,ack);yield null;
                }
                default -> throw new IllegalArgumentException("Unsupported public worker message");
            };
            if(result!=null)send(socket,session,"state.reconciled",correlation,result);
        } catch (RuntimeException e) {
            boolean version = "Unsupported API major version".equals(e.getMessage());
            boolean conflict = "Message ID reused with different report".equals(e.getMessage());
            JsonObject error = new JsonObject(); error.addProperty("code", version ? "unsupported_version" : conflict ? "message_id_conflict" : "invalid_session");
            error.addProperty("detail", version ? "Unsupported API major version" : conflict ? "Message ID reused with different report" : "Malformed or unsupported public worker message");
            send(socket, session, "protocol.error", correlation, error);
            socket.close(1008, "Invalid public worker session");
        }
    }
    private void hello(Session session,String version,JsonObject payload){
            if (!session.worker.equals(uuid(payload,"workerId")) || number(payload,"lastHostSequence") < 0 || number(payload,"lastWorkerSequence") < 0)
                throw new IllegalArgumentException("Worker identity or sequence mismatch");
            JsonArray versions = payload.getAsJsonArray("supportedVersions"), capabilities = payload.getAsJsonArray("capabilities");
            if (versions == null || versions.size() < 1 || versions.size() > 8 || capabilities == null || capabilities.size() > 64)
                throw new IllegalArgumentException("Invalid version or capability list");
            boolean supported = false; for (JsonElement offered : versions) if (version.equals(offered.getAsString())) supported = true;
            if (!supported) throw new IllegalArgumentException("Unsupported API major version");
            JsonObject implementation=null;
            if(payload.has("implementation")){
                implementation=payload.getAsJsonObject("implementation");
                String id=string(implementation,"id"),build=string(implementation,"version");
                if(id.length()>128 || !id.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*")
                    || build.length()>64 || !build.matches("[A-Za-z0-9][A-Za-z0-9._+-]*"))
                    throw new IllegalArgumentException("Invalid implementation identity");
            }else if(!LEGACY_VERSION.equals(version))throw new IllegalArgumentException("Missing implementation identity");
            Set<String> ids = new HashSet<>();
            for (JsonElement value : capabilities) {
                String id = string(value.getAsJsonObject(),"id");
                if (id.length() > 128 || !id.matches("[a-z][a-z0-9.-]*\\.[a-z][a-z0-9-]*\\.v[1-9][0-9]*") || !ids.add(id))
                    throw new IllegalArgumentException("Invalid or duplicate capability");
            }
            if(host!=null)host.publicWorkerHello(session.worker,ids,implementation);
    }
    private static String string(JsonObject object, String key) { return object.get(key).getAsString(); }
    private static UUID uuid(JsonObject object, String key) {
        String value=string(object,key);UUID id=UUID.fromString(value);
        if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException("Invalid UUID " + key);
        return id;
    }
    private static long number(JsonObject object, String key) { return object.get(key).getAsBigDecimal().longValueExact(); }
    boolean sendTo(UUID worker,String type,JsonObject payload) {
        WebSocket socket=active.get(worker);if(socket==null || !socket.isOpen())return false;
        Session session=socket.getAttachment();if(session==null || !session.accepted)return false;
        try {return send(socket,session,type,UUID.randomUUID(),payload);}
        catch(RuntimeException disconnected){return false;}
    }
    static boolean hasOutboundCapacity(int queuedFrames) { return queuedFrames < MAX_PENDING_FRAMES; }
    private static boolean send(WebSocket socket, Session session, String type, UUID correlation, JsonObject payload) {
        synchronized(session){
            if (!socket.isOpen()) return false;
            if (!hasOutboundCapacity(((WebSocketImpl)socket).outQueue.size())) {
                socket.closeConnection(1009, "Worker output queue full");return false;
            }
            JsonObject message = new JsonObject(); message.addProperty("apiVersion", session.version==null?VERSION:session.version); message.addProperty("type", type);
            message.addProperty("messageId", UUID.randomUUID().toString()); message.addProperty("correlationId", correlation.toString());
            message.addProperty("workerId", session.worker.toString());message.addProperty("sequence", session.nextSequence++);
            message.addProperty("sentAt", Instant.now().toString()); message.add("payload", payload);
            String wire=message.toString();
            if(wire.getBytes(StandardCharsets.UTF_8).length>MAX_FRAME){socket.closeConnection(1009,"Frame too large");return false;}
            socket.send(wire);return true;
        }
    }
    @Override public void onMessage(WebSocket socket, java.nio.ByteBuffer frame) { socket.closeConnection(1008, "Text frames required"); }
    @Override public void onClose(WebSocket socket, int code, String reason, boolean remote) {
        Session session = socket.getAttachment(); if (session != null && active.remove(session.worker, socket) && session.accepted && host!=null) host.publicWorkerDisconnected(session.worker);
    }
    @Override public void onError(WebSocket socket, Exception error) {
        if (socket != null) socket.closeConnection(1011, "Transport error");
        else { startupFailure = new IOException("Cannot start public worker listener", error); started.countDown(); }
    }
    @Override public void onStart() { started.countDown(); }
    @Override public void close() { try { stop(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
