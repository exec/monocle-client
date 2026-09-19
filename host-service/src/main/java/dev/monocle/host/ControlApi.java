package dev.monocle.host;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import dev.monocle.coordinator.TaskFiles;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Loopback operator API: native routes reject Origin; separate UI routes enforce same-origin bearer auth. */
public final class ControlApi implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), Thread.ofPlatform().name("Monocle local API-", 0).factory());
    public ControlApi(HostService host, int port, String token) throws IOException {
        this(host, port, token, "");
    }
    public ControlApi(HostService host, int port, String token, String uiOrigin) throws IOException {
        if (token == null || token.length() < 32 || token.length() > 128) throw new IllegalArgumentException("Invalid API token");
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        server.setExecutor(executor);
        WebUi ui;
        try { ui = new WebUi(server, uiOrigin); }
        catch (RuntimeException e) { server.stop(0); executor.shutdownNow(); throw e; }
        HttpHandler handler = exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                boolean browser = path.equals("/ui/api/status") || path.equals("/ui/api/control");
                if (browser) WebUi.headers(exchange);
                boolean status = path.equals("/v1/status") || path.equals("/ui/api/status");
                if (exchange.getRequestURI().getRawQuery() != null || !(status ? exchange.getRequestMethod().equals("GET")
                    : (path.equals("/control") || path.equals("/v1/control") || path.equals("/ui/api/control")) && exchange.getRequestMethod().equals("POST"))) {
                    respond(exchange, 405, "GET /v1/status or POST /v1/control required"); return;
                }
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if ((browser ? !ui.allowed(exchange, !status) : exchange.getRequestHeaders().containsKey("Origin"))
                    || auth == null || !MessageDigest.isEqual(expected, auth.getBytes(StandardCharsets.UTF_8))) { respond(exchange, 403, "Forbidden"); return; }
                if (status) {
                    JsonObject request = new JsonObject(); request.addProperty("op", "status");
                    send(exchange, 200, host.control(request)); return;
                }
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.equalsIgnoreCase("application/json")) { respond(exchange, 415, "application/json required"); return; }
                byte[] body = exchange.getRequestBody().readNBytes(TaskFiles.MAX_PACKAGE + 65_537);
                if (body.length > TaskFiles.MAX_PACKAGE + 65_536) { respond(exchange, 413, "Request too large"); return; }
                try {
                    JsonObject request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                    send(exchange, 200, host.control(request));
                } catch (RuntimeException | StackOverflowError e) { respond(exchange, 400, e instanceof StackOverflowError ? "JSON nesting exceeds limit" : "Request rejected: " + e.getMessage()); }
            }
        };
        for (String path : new String[]{"/control", "/v1/control", "/v1/status", "/ui/api/status", "/ui/api/control"}) server.createContext(path, handler);
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
