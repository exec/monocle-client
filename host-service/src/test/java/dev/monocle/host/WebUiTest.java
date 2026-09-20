package dev.monocle.host;

import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.util.Map;
import java.time.Duration;

final class WebUiTest {
    private static final String TOKEN = "ui-test-operator-token-12345678901234567890";
    static void run() throws Exception {
        try (HostService host = new HostService(Files.createTempDirectory("monocle-ui-check-"), "127.0.0.1", 0,
                 Map.of("Default", "ui-test-crew-key-12345678901234567890"), 30);
             ControlApi api = new ControlApi(host, 0, TOKEN);
             HttpClient http = HttpClient.newHttpClient()) {
            String origin = "http://127.0.0.1:" + api.port();
            var index = send(http, origin + "/ui/", null, null, false);
            assert index.statusCode() == 200 && index.body().contains("Control Room") && !index.body().contains(TOKEN);
            assert index.body().contains("value=\"highway\"") && index.body().contains("highway-worker-position");
            assert index.headers().firstValue("content-security-policy").orElseThrow().contains("frame-ancestors 'none'")
                && index.headers().firstValue("cache-control").orElseThrow().equals("no-store");
            String app = send(http, origin + "/ui/app.js", null, null, false).body();
            assert app.contains("textContent") && app.contains("submit-highway") && app.contains("Auto TPY") && app.contains("public-join") && !app.contains("localStorage");
            assert send(http, origin + "/ui/GlacialIndifference-Regular.otf", null, null, false).statusCode() == 200;
            assert send(http, origin + "/ui/GlacialIndifference-OFL.txt", null, null, false).body().contains("OPEN FONT LICENSE");
            for (String path : new String[]{"/ui/../host-config.json", "/ui/%2e%2e/host-config.json", "/ui/api/status?token=secret"})
                assert send(http, origin + path, null, null, false).statusCode() != 200;
            assert send(http, origin + "/ui/api/status", null, null, false).statusCode() == 403;
            assert send(http, origin + "/ui/api/status", "wrong", null, false).statusCode() == 403;
            assert send(http, origin + "/ui/api/status", TOKEN, origin, false).statusCode() == 200;
            assert send(http, origin + "/ui/api/status", TOKEN, "https://evil.invalid", false).statusCode() == 403;
            assert send(http, origin + "/ui/api/control", TOKEN, null, true).statusCode() == 403;
            assert send(http, origin + "/ui/api/control", TOKEN, "null", true).statusCode() == 403;
            assert send(http, origin + "/ui/api/control", TOKEN, "https://evil.invalid", true).statusCode() == 403;
            assert send(http, origin + "/ui/api/control", TOKEN, origin, true).statusCode() == 200;
            assert send(http, origin + "/v1/control", TOKEN, origin, true).statusCode() == 403 : "Browser UI cannot weaken native endpoint policy";
            var crossSite = HttpRequest.newBuilder(URI.create(origin + "/ui/api/status")).header("Authorization", "Bearer " + TOKEN).header("Sec-Fetch-Site", "cross-site").build();
            assert http.send(crossSite, HttpResponse.BodyHandlers.ofString()).statusCode() == 403;
            boolean invalid = false;
            try (ControlApi rejected = new ControlApi(host, 0, TOKEN, "http://evil.invalid")) { throw new AssertionError("Insecure external UI origin accepted"); }
            catch (IllegalArgumentException expected) { invalid = true; } assert invalid;
            try (ControlApi proxied = new ControlApi(host, 0, TOKEN, "https://bots.example.com"); TlsProxy tls = new TlsProxy(proxied.port());
                 HttpClient trusted = HttpClient.newBuilder().sslContext(TlsProxy.context()).build()) {
                // Simulate the exact Host/Origin forwarded by the configured TLS terminator.
                try (Socket socket = new Socket("127.0.0.1", proxied.port())) {
                    socket.setSoTimeout(5000);
                    String body = "{\"op\":\"end-highway\",\"crew\":\"Default\"}";
                    String request = "POST /ui/api/control HTTP/1.1\r\nHost: bots.example.com\r\nOrigin: https://bots.example.com\r\nAuthorization: Bearer " + TOKEN
                        + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assert response.startsWith("HTTP/1.1 200") : response;
                }
                // The loopback backend host must also match; Origin alone never authorizes proxy routing.
                assert send(trusted, "https://localhost:" + tls.port() + "/ui/api/status", TOKEN, "https://bots.example.com", false).statusCode() == 403;
            }
        }
        System.out.println("WebUI checks passed: bundled assets/font notices, bearer auth, same-origin mutations, cross-site rejection, native-policy isolation and exact HTTPS origin configuration.");
    }
    private static HttpResponse<String> send(HttpClient client, String url, String token, String origin, boolean post) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (origin != null) request.header("Origin", origin);
        if (post) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"end-highway\",\"crew\":\"Default\"}"));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
