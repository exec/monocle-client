package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.TaskFiles;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import static dev.monocle.coordinator.TaskWire.text;

public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !Set.of("init", "serve", "request").contains(args[0])) {
            System.out.println("Usage: monocle-host init|serve DATA_DIR\n       monocle-host request DATA_DIR '{\"op\":\"status\"}' (or @request.json)"); return;
        }
        Path directory = Path.of(args[1]).toAbsolutePath().normalize(), file = directory.resolve("host-config.json");
        if (args[0].equals("init")) {
            Files.createDirectories(directory);
            JsonObject config = new JsonObject(), crews = new JsonObject();
            config.addProperty("bind", "127.0.0.1"); config.addProperty("workerPort", 6969); config.addProperty("apiPort", 6970); config.addProperty("historyDays", 30);
            config.addProperty("webPort", 0);
            config.addProperty("autoTpy", false);
            config.addProperty("uiOrigin", "");
            config.addProperty("apiToken", secret()); crews.addProperty("Default", secret()); config.add("crews", crews);
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            else Files.createFile(file);
            TaskFiles.write(file, config);
            System.out.println("Created " + file + ". Copy the Default crew key into each worker's Workers settings. Treat this file as a password."); return;
        }
        if (!Files.isRegularFile(file) || Files.size(file) > 16_384) throw new IllegalArgumentException("Initialize the host data directory first");
        JsonObject config = TaskFiles.read(file);
        int apiPort = HostService.integer(config, "apiPort", 1, 65535);
        if (args[0].equals("request")) {
            if (args.length != 3) throw new IllegalArgumentException("Supply a JSON request or @file");
            JsonObject request = args[2].startsWith("@") ? TaskFiles.read(Path.of(args[2].substring(1))) : JsonParser.parseString(args[2]).getAsJsonObject();
            if (request.has("packageFile")) request.add("package", TaskFiles.read(Path.of(request.remove("packageFile").getAsString())));
            if (text(request, "op").equals("submit") && !request.has("id")) request.addProperty("id", UUID.randomUUID().toString());
            byte[] body = TaskFiles.jsonBytes(request, TaskFiles.MAX_PACKAGE + 65_536);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpRequest command = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/control")).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + text(config, "apiToken")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                var response = client.send(command, HttpResponse.BodyHandlers.ofString()); System.out.println(response.body());
                if (response.statusCode() != 200) throw new IllegalStateException("Control request rejected (HTTP " + response.statusCode() + ")");
            }
            return;
        }
        Map<String, String> crews = new LinkedHashMap<>(); config.getAsJsonObject("crews").entrySet().forEach(e -> crews.put(e.getKey(), e.getValue().getAsString()));
        int webPort = config.has("webPort") ? HostService.integer(config, "webPort", 0, 65535) : 0;
        try (HostService host = new HostService(directory, text(config, "bind"), HostService.integer(config, "workerPort", 1, 65535), crews, HostService.integer(config, "historyDays", -1, 3650), webPort == 0 ? -1 : webPort);
             ControlApi api = new ControlApi(host, apiPort, text(config, "apiToken"), text(config, "uiOrigin"))) {
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { api.close(); host.close(); stopped.countDown(); }, "Monocle host shutdown"));
            System.out.println("Monocle host listening at " + text(config, "bind") + ":" + host.port() + "; local control API 127.0.0.1:" + api.port());
            System.out.println("Operator WebUI: http://127.0.0.1:" + api.port() + "/ui/ (use the API token, not the crew key).");
            if (host.webPort() >= 0) System.out.println("Worker web ingress: ws://127.0.0.1:" + host.webPort() + "/v1/workers; remote workers require an HTTPS reverse proxy.");
            System.out.println("Capabilities: " + HostService.ACTIONS + ". Native highways use the shared coordinator; inspect status.highways for worker diagnostics.");
            stopped.await();
        }
    }
    private static String secret() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
