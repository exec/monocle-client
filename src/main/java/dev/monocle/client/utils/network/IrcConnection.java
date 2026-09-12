package dev.monocle.client.utils.network;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One explicit IRC-over-WebSocket session; no Minecraft state or credentials. */
public final class IrcConnection implements WebSocket.Listener, AutoCloseable {
    private final HttpClient client;
    private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(128);
    private final String nick, channel;
    private final StringBuilder fragment = new StringBuilder();
    private WebSocket socket;
    private CompletableFuture<WebSocket> opening;
    private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
    private int pending;
    private volatile boolean closed, joined;
    private volatile String status = "Connecting";

    public IrcConnection(URI endpoint, String nick, String channel) {
        validate(endpoint, nick, channel);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.nick = nick;
        this.channel = channel;
        opening = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .subprotocols("text.ircv3.net")
            .header("Origin", ("wss".equals(endpoint.getScheme()) ? "https" : "http") + "://" + endpoint.getRawAuthority())
            .buildAsync(endpoint, this);
        opening.whenComplete((ws, error) -> { if (error != null && !closed) fail("Connection failed"); });
    }

    public static void validate(URI endpoint, String nick, String channel) {
        String host = endpoint.getHost();
        boolean privateHost = host != null && (host.matches("10(\\.[0-9]{1,3}){3}")
            || host.matches("192\\.168(\\.[0-9]{1,3}){2}") || host.matches("127(\\.[0-9]{1,3}){3}")
            || host.matches("172\\.(1[6-9]|2[0-9]|3[01])(\\.[0-9]{1,3}){2}"));
        if (host == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getQuery() != null
            || !("wss".equals(endpoint.getScheme()) || "ws".equals(endpoint.getScheme()) && privateHost))
            throw new IllegalArgumentException("Use wss://, or ws:// with a private LAN IPv4 address. No credentials/query in URL.");
        if (!nick.matches("[A-Za-z][A-Za-z0-9_-]{0,23}") || !channel.matches("#[A-Za-z0-9_-]{1,40}"))
            throw new IllegalArgumentException("Use a simple nickname (max 24 characters) and #channel.");
    }

    @Override public synchronized void onOpen(WebSocket ws) {
        if (closed) { ws.abort(); return; }
        socket = ws;
        status = "Registering";
        send("NICK " + nick);
        send("USER monocle 0 * :Monocle LAN chat");
        ws.request(1);
    }

    private synchronized void send(String line) {
        if (closed || socket == null) return;
        if (++pending > 16) { fail("Outgoing queue full"); return; }
        WebSocket ws = socket;
        sending = sending.thenCompose(ignored -> closed ? CompletableFuture.completedFuture(null) : ws.sendText(line, true))
            .whenComplete((ignored, error) -> {
                synchronized (this) { pending--; }
                if (error != null && !closed) fail("Send failed");
            });
    }

    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        if (closed) return null;
        if (fragment.length() + data.length() > 8192) { fail("Oversized IRC frame"); return null; }
        fragment.append(data);
        if (last) {
            String frame = fragment.toString();
            fragment.setLength(0);
            for (String line : frame.split("\\r?\\n")) receive(line);
        }
        ws.request(1);
        return null;
    }

    private void receive(String line) {
        if (line.startsWith("@")) {
            int space = line.indexOf(' ');
            if (space < 0) return;
            line = line.substring(space + 1);
        }
        String prefix = "";
        if (line.startsWith(":")) {
            int space = line.indexOf(' ');
            if (space < 0) return;
            prefix = line.substring(1, space).split("!", 2)[0];
            line = line.substring(space + 1);
        }
        String[] parts = line.split(" ", 3);
        if (parts.length == 0) return;
        switch (parts[0]) {
            case "PING" -> { if (line.length() > 5) send("PONG " + line.substring(5)); }
            case "001" -> { status = "Joining " + channel; send("JOIN " + channel); }
            case "JOIN" -> {
                if (prefix.equalsIgnoreCase(nick)) { joined = true; status = "Connected " + channel; offer(status); }
            }
            case "PRIVMSG" -> {
                if (parts.length == 3 && parts[1].equalsIgnoreCase(channel))
                    offer("<" + plain(prefix) + "> " + plain(parts[2].startsWith(":") ? parts[2].substring(1) : parts[2]));
            }
            case "ERROR", "433", "432", "403", "405", "471", "473", "474", "475", "476" -> fail("IRC: " + plain(line));
            case "404", "442" -> offer("IRC: " + plain(line));
            case "KICK" -> { joined = false; fail("Removed from channel; reconnect manually"); }
            default -> { }
        }
    }

    private void offer(String message) { if (!messages.offer(message)) fail("Incoming queue full; disconnected"); }
    public String poll() { return messages.poll(); }
    public String status() { return status; }
    public boolean joined() { return joined && !closed; }

    public static String message(String text) {
        if (text.isBlank() || text.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0xA7)
            || text.getBytes(StandardCharsets.UTF_8).length > 350)
            throw new IllegalArgumentException("Use 1–350 UTF-8 bytes of plain chat text, without control characters.");
        return text;
    }

    public void say(String text) {
        text = message(text);
        if (!joined()) throw new IllegalStateException("IRC is not connected to the channel.");
        send("PRIVMSG " + channel + " :" + text);
        offer("<" + nick + "> " + plain(text));
    }

    public static String plain(String text) {
        StringBuilder clean = new StringBuilder();
        text.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != 0xA7)
            .limit(600).forEach(clean::appendCodePoint);
        return clean.toString();
    }

    private synchronized void fail(String reason) { if (!closed) { close(); status = reason; } }
    @Override public void onError(WebSocket ws, Throwable error) { fail("WebSocket disconnected"); }
    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { fail("Disconnected (" + code + ")"); return null; }
    @Override public synchronized void close() {
        closed = true;
        joined = false;
        if (socket != null) socket.abort();
        if (opening != null) opening.cancel(true);
        client.shutdownNow();
        status = "Disconnected";
    }
}
