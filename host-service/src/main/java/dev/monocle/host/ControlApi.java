package dev.monocle.host;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import dev.monocle.coordinator.TaskFiles;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
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
        server.createContext("/v1/", exchange -> {
            try (exchange) {
                String correlation = UUID.randomUUID().toString();
                try {
                if (exchange.getRequestHeaders().containsKey("Origin")) { problem(exchange, 403, "forbidden", "Origin is not accepted", correlation); return; }
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !MessageDigest.isEqual(expected, auth.getBytes(StandardCharsets.UTF_8))) { problem(exchange, 403, "forbidden", "Forbidden", correlation); return; }
                JsonObject body = new JsonObject();
                if (ResourceApi.bodyRequired(exchange.getRequestMethod())) {
                    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (contentType == null || !contentType.equalsIgnoreCase("application/json")) { problem(exchange, 415, "unsupported_media_type", "application/json required", correlation); return; }
                    byte[] bytes = exchange.getRequestBody().readNBytes(TaskFiles.MAX_PACKAGE + 65_537);
                    if (bytes.length > TaskFiles.MAX_PACKAGE + 65_536) { problem(exchange, 413, "request_too_large", "Request too large", correlation); return; }
                    body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                } else if (exchange.getRequestHeaders().getFirst("Content-Length") != null && !exchange.getRequestHeaders().getFirst("Content-Length").equals("0")) {
                    problem(exchange, 400, "body_not_allowed", "Request body is not allowed", correlation); return;
                }
                String method = exchange.getRequestMethod();
                if (method.equals("GET")) {
                    ResourceApi.Reply reply = ResourceApi.route(host, method, exchange.getRequestURI(), body, null);
                    reply = standardize(reply, correlation, exchange.getRequestURI().getPath());
                    send(exchange, reply.code(), reply.body()); return;
                }
                String commandId = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                if (commandId == null) { problem(exchange, 428, "idempotency_key_required", "Supply a UUID Idempotency-Key", correlation); return; }
                commandId = UUID.fromString(commandId).toString(); correlation = commandId;
                String fingerprint = TaskFiles.hash(method + "\n" + exchange.getRequestURI() + "\n" + exchange.getRequestHeaders().getFirst("If-Match") + "\n" + body);
                ResourceApi.Reply reply;
                synchronized (host) {
                    OperatorOperations operations = host.operatorOperations();
                    JsonObject prior = operations.get(commandId);
                    operations.begin(commandId, fingerprint);
                    if (prior != null) {
                        if (!prior.has("response")) {
                            JsonObject uncertain = new JsonObject(); uncertain.addProperty("operationId", commandId);
                            uncertain.addProperty("state", "outcome_uncertain"); uncertain.addProperty("correlationId", commandId);
                            reply = new ResourceApi.Reply(202, uncertain);
                        } else reply = new ResourceApi.Reply(prior.get("httpStatus").getAsInt(), prior.getAsJsonObject("response"));
                    } else {
                        try {
                            reply = ResourceApi.route(host, method, exchange.getRequestURI(), body, exchange.getRequestHeaders().getFirst("If-Match"));
                        } catch (ResourceApi.Precondition e) {
                            reply = new ResourceApi.Reply(e.status, problemBody(e.status, e.status == 428 ? "precondition_required" : "revision_mismatch", e.getMessage(), commandId, exchange.getRequestURI().getPath()));
                        } catch (IllegalStateException e) {
                            reply = new ResourceApi.Reply(409, problemBody(409, "conflict", e.getMessage(), commandId, exchange.getRequestURI().getPath()));
                        } catch (RuntimeException | StackOverflowError e) {
                            reply = new ResourceApi.Reply(400, problemBody(400, "invalid_request", e instanceof StackOverflowError ? "JSON nesting exceeds limit" : e.getMessage(), commandId, exchange.getRequestURI().getPath()));
                        }
                        reply = standardize(reply, commandId, exchange.getRequestURI().getPath());
                        boolean async = reply.code() < 400 && asynchronous(method, exchange.getRequestURI().getPath());
                        int code = async ? 202 : reply.code();
                        JsonObject result = reply.body().deepCopy();
                        result.addProperty("operationId", commandId); result.addProperty("correlationId", commandId);
                        reply = new ResourceApi.Reply(code, result);
                        operations.finish(commandId, code, result, async);
                        host.operatorReceipt(commandId, async ? "host_accepted" : "completed");
                    }
                }
                send(exchange, reply.code(), reply.body());
                } catch (OperatorOperations.JournalException e) { problem(exchange, 503, "receipt_unavailable", e.getMessage(), correlation); }
                  catch (IllegalStateException e) { problem(exchange, 409, "conflict", e.getMessage(), correlation); }
                  catch (IllegalArgumentException | StackOverflowError e) { problem(exchange, 400, "invalid_request", e instanceof StackOverflowError ? "JSON nesting exceeds limit" : e.getMessage(), correlation); }
            }
        });
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    private static void respond(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject error = new JsonObject(); error.addProperty("error", message); send(exchange, code, error);
    }
    private static boolean asynchronous(String method, String path) {
        return path.equals("/v1/jobs") || path.matches("/v1/jobs/[^/]+/(pause|resume|cancel|release|configuration)")
            || path.matches("/v1/jobs/[^/]+/workers/[^/]+/(detach|rejoin)")
            || path.matches("/v1/crews/[^/]+/workers/[^/]+") || path.matches("/v1/drafts/[^/]+/dispatch")
            || path.matches("/v1/stashes/[^/]+/scan");
    }
    private static void problem(HttpExchange exchange, int status, String code, String detail, String correlation) throws IOException {
        send(exchange, status, problemBody(status, code, detail, correlation, exchange.getRequestURI().getPath()));
    }
    private static JsonObject problemBody(int status, String code, String detail, String correlation, String path) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "https://workers.monocle.dev/problems/" + code.replace('_', '-'));
        result.addProperty("title", code.replace('_', ' ')); result.addProperty("status", status);
        result.addProperty("code", code); result.addProperty("detail", detail == null ? "Request rejected" : detail);
        result.addProperty("instance", path); result.addProperty("retryable", status == 503);
        result.addProperty("correlationId", correlation); return result;
    }
    private static ResourceApi.Reply standardize(ResourceApi.Reply reply, String correlation, String path) {
        if (reply.code() < 400 || reply.body().has("type")) return reply;
        String code = reply.body().has("code") ? reply.body().get("code").getAsString() : reply.code() == 404 ? "not_found" : "conflict";
        JsonObject problem = problemBody(reply.code(), code,
            reply.body().has("error") ? reply.body().get("error").getAsString() : "Refresh the resource snapshot and resume from its latest event cursor", correlation, path);
        if (reply.body().has("nextCursor")) problem.add("nextCursor", reply.body().get("nextCursor"));
        if (reply.body().has("snapshotRequired")) problem.add("snapshotRequired", reply.body().get("snapshotRequired"));
        return new ResourceApi.Reply(reply.code(), problem);
    }
    private static void send(HttpExchange exchange, int code, JsonObject value) throws IOException {
        byte[] body = value.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", value.has("type") ? "application/problem+json; charset=utf-8" : "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, body.length); exchange.getResponseBody().write(body);
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
