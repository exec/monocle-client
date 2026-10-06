package dev.monocle.host;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Bundled same-origin operator UI. No cookie auth, remote bind or caller-selected files. */
final class WebUi {
    private final Set<String> origins = new HashSet<>();
    private final Set<String> authorities = new HashSet<>();
    private static final Map<String, String> ASSETS = Map.of(
        "/ui/", "index.html", "/ui/index.html", "index.html", "/ui/app.js", "app.js", "/ui/style.css", "style.css",
        "/ui/GlacialIndifference-Regular.otf", "GlacialIndifference-Regular.otf",
        "/ui/GlacialIndifference-OFL.txt", "GlacialIndifference-OFL.txt", "/ui/GlacialIndifference-NOTICE.txt", "GlacialIndifference-NOTICE.txt");

    WebUi(HttpServer server, String configuredOrigin) {
        int port = server.getAddress().getPort();
        origins.add("http://127.0.0.1:" + port); origins.add("http://localhost:" + port);
        if (configuredOrigin != null && !configuredOrigin.isBlank()) {
            URI uri;
            try { uri = URI.create(configuredOrigin); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("uiOrigin must be an exact HTTPS origin"); }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")) || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException("uiOrigin must be an exact HTTPS origin, e.g. https://bots.example.com");
            origins.add("https://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() < 0 || uri.getPort() == 443 ? "" : ":" + uri.getPort()));
        }
        for (String origin : origins) authorities.add(URI.create(origin).getRawAuthority().toLowerCase(Locale.ROOT));
        server.createContext("/ui", exchange -> {
            try (exchange) {
                headers(exchange);
                if (!allowed(exchange, false)) { exchange.sendResponseHeaders(403, -1); return; }
                if (exchange.getRequestURI().getPath().equals("/ui") && exchange.getRequestMethod().equals("GET")) {
                    exchange.getResponseHeaders().set("Location", "/ui/"); exchange.sendResponseHeaders(302, -1); return;
                }
                String file = ASSETS.get(exchange.getRequestURI().getRawPath());
                if (file == null || !exchange.getRequestMethod().equals("GET") || exchange.getRequestURI().getRawQuery() != null) {
                    exchange.sendResponseHeaders(404, -1); return;
                }
                try (InputStream asset = WebUi.class.getResourceAsStream("/webui/" + file)) {
                    if (asset == null) { exchange.sendResponseHeaders(404, -1); return; }
                    String type = file.endsWith(".html") ? "text/html; charset=utf-8" : file.endsWith(".js") ? "text/javascript; charset=utf-8"
                        : file.endsWith(".css") ? "text/css; charset=utf-8" : file.endsWith(".otf") ? "font/otf" : "text/plain; charset=utf-8";
                    byte[] bytes = asset.readAllBytes(); exchange.getResponseHeaders().set("Content-Type", type);
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
            }
        });
    }
    boolean allowed(HttpExchange exchange, boolean mutation) {
        String authority = exchange.getRequestHeaders().getFirst("Host");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        String reason = authority == null || !authorities.contains(authority.toLowerCase(Locale.ROOT)) ? "unrecognized Host"
            : site != null && !site.equals("same-origin") && !site.equals("none") ? "cross-site fetch"
            : origin == null && mutation ? "missing Origin on mutation"
            : origin != null && !origins.contains(origin) ? "unrecognized Origin"
            : origin != null && !URI.create(origin).getRawAuthority().equalsIgnoreCase(authority) ? "Origin/Host mismatch" : null;
        if (reason == null) return true;
        System.err.printf("[WebUI] %s rejected %s %s: %s (Host=%s, Origin=%s, Sec-Fetch-Site=%s)%n",
            Instant.now(), safe(exchange.getRequestMethod()), safe(exchange.getRequestURI().getPath()), reason,
            safe(authority), safe(origin), safe(site));
        return false;
    }
    private static String safe(String value) {
        return value == null ? "<missing>" : value.substring(0, Math.min(value.length(), 160)).replaceAll("\\p{Cntrl}", "?");
    }
    static void headers(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store"); headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer"); headers.set("X-Frame-Options", "DENY");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    }
}
