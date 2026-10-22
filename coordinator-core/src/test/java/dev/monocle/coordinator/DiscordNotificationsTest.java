package dev.monocle.coordinator;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

final class DiscordNotificationsTest {
    private static final String TOKEN="fake_bot_token_not_a_secret_123456789";
    private static JsonObject config(boolean enabled) {
        JsonObject request=new JsonObject();request.addProperty("enabled",enabled);request.addProperty("ownerId","123456789012345678");request.addProperty("botToken",TOKEN);return request;
    }
    private static void await(BooleanSupplier check) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!check.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(20);
        assert check.getAsBoolean():"Timed out waiting for asynchronous Discord delivery";
    }
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("Expected validation rejection");}catch(IllegalArgumentException|IllegalStateException expected){ }}
    static void run() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger attempts=new AtomicInteger(),mode=new AtomicInteger(429),created=new AtomicInteger();
        List<JsonObject> messages=Collections.synchronizedList(new ArrayList<>());
        server.createContext("/",exchange->{
            assert exchange.getRequestHeaders().getFirst("Authorization").equals("Bot "+TOKEN);
            JsonObject request=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            int status;String response;
            if(exchange.getRequestURI().getPath().equals("/users/@me/channels")){
                created.incrementAndGet();assert request.get("recipient_id").getAsString().equals("123456789012345678");status=200;response="{\"id\":\"987654321012345678\"}";
            }else{
                messages.add(request);attempts.incrementAndGet();
                assert request.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty();
                assert request.get("enforce_nonce").getAsBoolean();assert request.get("content").getAsString().length()<=1900;
                status=mode.getAndUpdate(value->value==429?200:value);response=status==429?"{\"retry_after\":1.1}":status==403?"{\"message\":\""+TOKEN+"\"}":"{\"id\":\"123456789123456789\"}";
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        URI endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
        try {
            Path directory=Files.createTempDirectory("discord-delivery-check-");
            try(DiscordNotifications notifications=new DiscordNotifications(directory,endpoint)){
                JsonObject invalid=config(true);invalid.addProperty("ownerId","18446744073709551616");rejects(()->notifications.configure(invalid));
                notifications.configure(config(true));assert !notifications.status().toString().contains(TOKEN);
                if(Files.getFileStore(directory).supportsFileAttributeView("posix"))assert Files.getPosixFilePermissions(directory.resolve("discord-notifications.json")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                notifications.test();await(()->attempts.get()==1);
                await(()->notifications.status().get("delivered").getAsInt()==1);
                assert attempts.get()==2 && created.get()==1;
                assert messages.get(0).get("nonce").equals(messages.get(1).get("nonce")):"Retries keep the same Discord deduplication nonce";
                mode.set(403);notifications.test();await(()->notifications.status().get("blocked").getAsBoolean());
                assert !notifications.status().toString().contains(TOKEN):"Discord error bodies must not leak credentials";
                int blockedAttempts=attempts.get();Thread.sleep(1200);assert attempts.get()==blockedAttempts;
                mode.set(200);JsonObject retain=config(true);retain.addProperty("botToken","");notifications.configure(retain);
                await(()->notifications.status().get("delivered").getAsInt()==2);
                mode.set(500);notifications.test();await(()->notifications.status().get("error").getAsString().contains("HTTP 500"));
                assert notifications.status().get("retryAt").getAsLong()>System.currentTimeMillis()+5000;
                int backoffAttempts=attempts.get();Thread.sleep(150);assert attempts.get()==backoffAttempts;
                mode.set(200);notifications.configure(retain);await(()->notifications.status().get("delivered").getAsInt()==3);
            }
            mode.set(403);
            Path tasks=Files.createTempDirectory("discord-events-check-");
            JsonObject job=new JsonObject();job.addProperty("id",UUID.randomUUID().toString());job.addProperty("name","Test highway");job.addProperty("crew","Default");job.addProperty("status","Running");job.addProperty("detail","building");
            JsonObject runs=new JsonObject(),run=new JsonObject();run.addProperty("status","Running");run.addProperty("detail","checking resources");runs.add("worker-one",run);job.add("runs",runs);
            try(DiscordNotifications notifications=new DiscordNotifications(tasks,endpoint)){
                notifications.configure(config(true));notifications.prime(job);
                run.addProperty("requestedStatus","Failed");run.addProperty("detail","out of food");notifications.observeTask(job);notifications.observeTask(job);
                assert notifications.status().get("pending").getAsInt()==1;
                job.addProperty("status","Failed");notifications.observeTask(job);assert notifications.status().get("pending").getAsInt()==1:"Job/run failure must not double-alert";
                run.remove("requestedStatus");run.addProperty("status","Running");job.addProperty("status","Running");notifications.observeTask(job);
                run.addProperty("status","Inspection required");notifications.observeTask(job);assert notifications.status().get("pending").getAsInt()==2;
                run.addProperty("status","Complete");job.addProperty("status","Complete");notifications.observeTask(job);notifications.observeTask(job);assert notifications.status().get("pending").getAsInt()==3;
                notifications.failure("worker-disconnected","worker-one","connection lost");notifications.failure("worker-disconnected","worker-one","connection lost");assert notifications.status().get("pending").getAsInt()==4;
                JsonObject worker=new JsonObject();worker.addProperty("id",UUID.randomUUID().toString());worker.addProperty("name","Worker");worker.addProperty("phase","blocked");worker.addProperty("status","Out of inventory space");
                notifications.observeWorker(worker);notifications.observeWorker(worker);assert notifications.status().get("pending").getAsInt()==5;
                JsonObject incident=new JsonObject(),evidence=new JsonObject();incident.addProperty("id",TaskFiles.hash("death"));incident.addProperty("workerId",worker.get("id").getAsString());incident.addProperty("workerName","Worker");incident.addProperty("kind","death");incident.add("evidence",evidence);
                notifications.incident(incident);notifications.incident(incident);assert notifications.status().get("pending").getAsInt()==6;
                incident.addProperty("id",TaskFiles.hash("safety"));incident.addProperty("kind","hostile_logout");evidence.addProperty("name","Enemy");notifications.incident(incident);assert notifications.status().get("pending").getAsInt()==7;
                incident.addProperty("kind","sighting");notifications.incident(incident);assert notifications.status().get("pending").getAsInt()==7:"Neutral sightings do not alert";
                run.addProperty("configError","Unknown module");notifications.observeTask(job);notifications.observeTask(job);assert notifications.status().get("pending").getAsInt()==8:"Live configuration errors alert once";
                notifications.failure("worker-error","Worker","🌸".repeat(2000)+TOKEN);assert notifications.status().get("pending").getAsInt()==9;
                run.remove("configError");run.addProperty("status","Suspending");job.addProperty("status","Running");run.addProperty("stallPaused",true);
                JsonObject progress=JsonParser.parseString("{phase:'Stash deposit',expectedNextEvent:'Server inventory confirmation',progressAgeMs:120000,recoveryAttempts:0,state:'Paused'}").getAsJsonObject();progress.addProperty("incidentId",UUID.randomUUID().toString());run.add("actionProgress",progress);
                notifications.observeTask(job);notifications.observeTask(job);assert notifications.status().get("pending").getAsInt()==10:"One stalled action sends one owner alert, before cleanup finishes";
                run.add("stallIncidentId",progress.get("incidentId").deepCopy());progress.addProperty("state","Working");progress.remove("incidentId");notifications.observeTask(job);
                assert notifications.status().get("pending").getAsInt()==10:"Pre-stall telemetry cannot erase authoritative incident identity or create another alert";
            }
            try(DiscordNotifications notifications=new DiscordNotifications(tasks,endpoint)){
                assert notifications.status().get("pending").getAsInt()==10:"Pending notifications and long Unicode messages survive a restart";
                notifications.observeTask(job);assert notifications.status().get("pending").getAsInt()==10:"A saved stall incident is not announced twice";
                run.remove("stallPaused");notifications.observeTask(job);assert notifications.status().get("pending").getAsInt()==10;
                run.addProperty("stallPaused",true);run.addProperty("stallIncidentId",UUID.randomUUID().toString());notifications.observeTask(job);assert notifications.status().get("pending").getAsInt()==11:"A new stall episode alerts again";
                JsonObject next=config(false);next.addProperty("ownerId","223456789012345678");notifications.configure(next);assert notifications.status().get("pending").getAsInt()==0:"A new owner must not receive the old owner's backlog";
            }
            Path corrupt=Files.createTempDirectory("discord-corrupt-check-");
            JsonObject broken=new JsonObject();broken.addProperty("version",1);TaskFiles.write(corrupt.resolve("discord-outbox.json"),broken);
            try(DiscordNotifications notifications=new DiscordNotifications(corrupt,endpoint)){notifications.configure(config(true));rejects(notifications::test);}
            assert TaskFiles.read(corrupt.resolve("discord-outbox.json")).equals(broken):"Never overwrite an unreadable outbox";
            System.out.println("Discord checks passed: private credentials, local REST DMs, 429 retry nonce, blocked delivery, failure/inspection/completion/death/safety deduplication, restart and corrupt-outbox preservation.");
        } finally{server.stop(0);}
    }
}
