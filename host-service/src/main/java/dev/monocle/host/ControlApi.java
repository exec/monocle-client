package dev.monocle.host;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import dev.monocle.coordinator.TaskFiles;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Local native-launcher API. Never exposed on LAN; bearer auth and no browser-origin requests. */
public final class ControlApi implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), Thread.ofPlatform().name("Monocle local API-", 0).factory());
    public ControlApi(HostService host, int port, String token) throws IOException {
        if (token == null || token.length() < 32 || token.length() > 128) throw new IllegalArgumentException("Invalid API token");
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        server.setExecutor(executor);
        server.createContext("/control", exchange -> {
            try (exchange) {
                if (!exchange.getRequestURI().getPath().equals("/control") || !exchange.getRequestMethod().equals("POST")) { respond(exchange, 405, "POST /control required"); return; }
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (exchange.getRequestHeaders().containsKey("Origin") || auth == null || !MessageDigest.isEqual(expected, auth.getBytes(StandardCharsets.UTF_8))) { respond(exchange, 403, "Forbidden"); return; }
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.equalsIgnoreCase("application/json")) { respond(exchange, 415, "application/json required"); return; }
                byte[] body = exchange.getRequestBody().readNBytes(TaskFiles.MAX_PACKAGE + 65_537);
                if (body.length > TaskFiles.MAX_PACKAGE + 65_536) { respond(exchange, 413, "Request too large"); return; }
                try {
                    JsonObject request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                    send(exchange, 200, host.control(request));
                } catch (RuntimeException | StackOverflowError e) { respond(exchange, 400, e instanceof StackOverflowError ? "JSON nesting exceeds limit" : "Request rejected: " + e.getMessage()); }
            }
        });
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    private static void respond(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject error = new JsonObject(); error.addProperty("error", message); send(exchange, code, error);
    }
    private static void send(HttpExchange exchange, int code, JsonObject value) throws IOException {
        byte[] body = value.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, body.length); exchange.getResponseBody().write(body);
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
