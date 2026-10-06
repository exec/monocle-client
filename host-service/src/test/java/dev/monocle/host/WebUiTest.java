package dev.monocle.host;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.monocle.coordinator.TaskFiles;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;

final class WebUiTest {
    private static final String TOKEN = "ui-test-operator-token-12345678901234567890";
    static void run() throws Exception {
        Path directory=Files.createTempDirectory("monocle-ui-check-");
        Map<String,String> crews=Map.of("Default", "ui-test-crew-key-12345678901234567890");
        JsonObject config=new JsonObject();config.add("crews",new Gson().toJsonTree(crews));TaskFiles.write(directory.resolve("host-config.json"),config);
        try (HostService host = new HostService(directory, "127.0.0.1", 0, crews, 30);
             ControlApi api = new ControlApi(host, 0, TOKEN);
             HttpClient http = HttpClient.newHttpClient()) {
            String origin = "http://127.0.0.1:" + api.port();
            var index = send(http, origin + "/ui/", null, null, false);
            assert index.statusCode() == 200 && index.body().contains("Control Room") && !index.body().contains(TOKEN);
            assert index.body().contains("value=\"highway\"") && index.body().contains("highway-worker-position");
            assert index.body().contains("id=\"kit-field\"") && index.body().contains("id=\"kit-recipient-worker\"") : "Kit dispatch uses guided fields in the existing job review";
            assert index.headers().firstValue("content-security-policy").orElseThrow().contains("frame-ancestors 'none'")
                && index.headers().firstValue("cache-control").orElseThrow().equals("no-store");
            String app = send(http, origin + "/ui/app.js", null, null, false).body();
            assert app.contains("textContent") && app.contains("workflow-prepare") && app.contains("Auto TPY") && app.contains("public-join") && !app.contains("localStorage");
            assert app.contains("sessionStorage.getItem(tokenKey)") && app.contains("sessionStorage.setItem(tokenKey, token)")
                && app.contains("sessionStorage.removeItem(tokenKey)") && app.contains("if (path === 'status' && body.error === 'Invalid API token' && attempt === generation) clearToken()")
                : "Operator token survives a reload, but only status authentication failure or explicit disconnect erases it";
            assert app.contains("mode: 'cors'") && !app.contains("mode: 'same-origin'")
                : "No-referrer plus same-origin fetch mode can send Origin: null on a POST";
            assert app.contains("loadInspection()") && app.contains("resource('GET',selected.kind+'s/'+selected.id)")
                : "Job and worker inspectors must fetch resource records";
            assert app.contains("stashResourceId(stash)") && !app.contains("op:'stash-get'")
                : "Stash inspection must use its resource ID instead of legacy control";
            assert app.contains("kitUsage(snapshot)") && app.contains("task-kit-remove-incomplete") && app.contains("updateKitSources()")
                : "Stash view exposes kit stock, usage, and guided dispatch";
            assert app.contains("transferAll:$('kit-all').checked")&&app.contains("count>36")&&app.contains("job.kitJob.delivered??")
                : "Repeat kit jobs expose a 36-slot cap and count actual completed deliveries, not their per-trip cap";
            assert app.indexOf("if(context.preset){$('job-kind').value='preset'") < app.indexOf("$('job-dialog').showModal()")
                && app.contains("if(context.preset&&!presetList.some(p=>p.id===context.preset))throw Error")
                && app.contains("$('job-submit').disabled=!loaded") : "Contextual kit jobs must not fall back to a dispatchable highway form";
            assert app.contains("shulkerLabel(row,item)") && app.contains("shulkerLabel(row,type.item)")
                && send(http, origin + "/ui/style.css", null, null, false).body().contains(".shulker-row{background:color-mix")
                : "Shulker stock and kit rows have readable names and restrained color tints";
            assert app.contains("'stashes/'+submission.stashId+'/scan'") && app.contains("scanJobId=submission.stashId?job.id:null")
                : "Saved stash scans must dispatch through the resource route and open the resulting job";
            assert send(http, origin + "/ui/GlacialIndifference-Regular.otf", null, null, false).statusCode() == 200;
            assert send(http, origin + "/ui/GlacialIndifference-OFL.txt", null, null, false).body().contains("OPEN FONT LICENSE");
            for (String path : new String[]{"/ui/../host-config.json", "/ui/%2e%2e/host-config.json", "/ui/api/status?token=secret"})
                assert send(http, origin + path, null, null, false).statusCode() != 200;
            assert send(http, origin + "/ui/api/status", null, null, false).statusCode() == 403;
            assert send(http, origin + "/ui/api/status", "wrong", null, false).body().contains("Invalid API token");
            assert send(http, origin + "/ui/api/status", TOKEN, origin, false).statusCode() == 200;
            assert send(http, origin + "/ui/api/status", TOKEN, "https://evil.invalid", false).statusCode() == 403;
            assert send(http, origin + "/ui/api/control", TOKEN, null, true).body().contains("UI origin is not accepted");
            assert send(http, origin + "/ui/api/control", TOKEN, "null", true).statusCode() == 403;
            assert send(http, origin + "/ui/api/control", TOKEN, "https://evil.invalid", true).statusCode() == 403;
            assert send(http, origin + "/ui/api/control", TOKEN, origin, true).statusCode() == 200;
            assert send(http, origin + "/ui/api/v1/workers", TOKEN, origin, false).statusCode() == 200;
            assert send(http, origin + "/ui/api/v1/workers", TOKEN, "https://evil.invalid", false).statusCode() == 403;
            String crewBody="{\"id\":\""+UUID.randomUUID()+"\",\"name\":\"UI resource crew\"}",commandId=UUID.randomUUID().toString();
            var uiCreate=HttpRequest.newBuilder(URI.create(origin+"/ui/api/v1/crews"))
                .header("Authorization","Bearer "+TOKEN).header("Origin",origin).header("Content-Type","application/json")
                .header("Idempotency-Key",commandId).POST(HttpRequest.BodyPublishers.ofString(crewBody)).build();
            var created=http.send(uiCreate,HttpResponse.BodyHandlers.ofString());
            assert created.statusCode()==201 : created.body();
            JsonObject crew=new Gson().fromJson(created.body(),JsonObject.class);
            var detail=send(http,origin+"/ui/api/v1/crews/"+crew.get("id").getAsString(),TOKEN,origin,false);
            assert detail.statusCode()==200 && new Gson().fromJson(detail.body(),JsonObject.class).get("revision").equals(crew.get("revision"))
                : "Crew inspection uses the same resource identity and revision as the list";
            var nativeReplay=HttpRequest.newBuilder(URI.create(origin+"/v1/crews"))
                .header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
                .header("Idempotency-Key",commandId).POST(HttpRequest.BodyPublishers.ofString(crewBody)).build();
            assert http.send(nativeReplay,HttpResponse.BodyHandlers.ofString()).body().equals(created.body()) : "UI and native resource routes share durable receipts";
            var rename=HttpRequest.newBuilder(URI.create(origin+"/ui/api/v1/crews/"+crew.get("id").getAsString()))
                .header("Authorization","Bearer "+TOKEN).header("Origin",origin).header("Content-Type","application/json")
                .header("Idempotency-Key",UUID.randomUUID().toString()).header("If-Match",crew.get("revision").getAsString())
                .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"name\":\"Renamed UI crew\"}")).build();
            assert http.send(rename,HttpResponse.BodyHandlers.ofString()).statusCode()==200 : "UI rename requires the same guarded resource semantics";
            assert http.send(rename,HttpResponse.BodyHandlers.ofString()).statusCode()==200 : "Identical command replay keeps its receipt";
            var delete=HttpRequest.newBuilder(URI.create(origin+"/ui/api/v1/crews/"+crew.get("id").getAsString()))
                .header("Authorization","Bearer "+TOKEN).header("Origin",origin)
                .header("Idempotency-Key",UUID.randomUUID().toString()).DELETE().build();
            assert http.send(delete,HttpResponse.BodyHandlers.ofString()).statusCode()==200 : "Empty UI crew can be removed through its resource";
            assert send(http, origin + "/ui/api/v1/crews", TOKEN, null, true).statusCode() == 403 : "UI mutations require a matching Origin";
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
