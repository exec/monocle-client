package dev.monocle.coordinator;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static dev.monocle.coordinator.TaskWire.*;
import static dev.monocle.coordinator.StashCatalog.integer;

/** Private, best-effort owner DMs. Gameplay never waits on Discord or notification journal IO. */
public final class DiscordNotifications implements AutoCloseable {
    private final Path configFile, journal;
    private final URI api;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("Discord notifications").factory());
    private final LinkedHashMap<String, String> states = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> alerts = new LinkedHashMap<>();
    private final Deque<JsonObject> pending = new ArrayDeque<>();
    private final Object journalLock = new Object();
    private String token = "", owner = "", channel = "", error = "";
    private String persistedHead="";
    private boolean enabled, blocked, dirty, closed, journalHealthy=true;
    private long revision, retryAt, delivered, dropped, lastDelivered;

    public DiscordNotifications(Path directory) { this(directory, URI.create("https://discord.com/api/v10/")); }
    DiscordNotifications(Path directory, URI api) {
        this.api = api; configFile = directory.resolve("discord-notifications.json"); journal = directory.resolve("discord-outbox.json");
        reload();
        try {
            JsonObject saved = TaskFiles.read(journal); TaskFiles.jsonBytes(saved, 4 * 1024 * 1024);
            if (!saved.isEmpty()) {
                if (integer(saved,"version",1,1)!=1 || saved.getAsJsonObject("states").size()>2048 || saved.getAsJsonObject("alerts").size()>1024 || saved.getAsJsonArray("pending").size()>256) throw new IllegalArgumentException();
                for (var entry:saved.getAsJsonObject("states").entrySet()) {
                    if(entry.getKey().length()>200 || entry.getValue().getAsString().length()>100)throw new IllegalArgumentException();
                    states.put(entry.getKey(),entry.getValue().getAsString());
                }
                for(var entry:saved.getAsJsonObject("alerts").entrySet()) {
                    if(entry.getKey().length()>200)throw new IllegalArgumentException();
                    alerts.put(entry.getKey(),entry.getValue().getAsBigDecimal().longValueExact());
                }
                for(JsonElement value:saved.getAsJsonArray("pending")) {
                    JsonObject message=value.getAsJsonObject();
                    if(!text(message,"id").matches("[a-f0-9]{24}") || text(message,"content").length()>1900 || integer(message,"attempts",0,100000)<0)throw new IllegalArgumentException();
                    pending.add(message.deepCopy());
                }
                if(!text(saved,"ownerId").equals(owner)){pending.clear();dirty=true;}
                if(!pending.isEmpty())persistedHead=text(pending.getFirst(),"id");
            }
        } catch(RuntimeException e) { pending.clear(); states.clear(); alerts.clear(); blocked=true;journalHealthy=false; error="Notification outbox is unreadable; restore or move discord-outbox.json before restarting. Original left untouched."; }
        sender.scheduleWithFixedDelay(this::pump, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void reload() {
        try {
            JsonObject config=TaskFiles.read(configFile); TaskFiles.jsonBytes(config,4096);
            if(config.isEmpty())return;
            configure(config,false);
            privateFile(configFile);
        } catch(RuntimeException e) { enabled=false; error="Cannot load Discord settings. Check discord-notifications.json; original left untouched."; }
    }
    public synchronized JsonObject configure(JsonObject request) { configure(request,true); return status(); }
    private void configure(JsonObject request, boolean save) {
        if(!request.has("enabled") || !request.get("enabled").isJsonPrimitive() || !request.getAsJsonPrimitive("enabled").isBoolean())throw new IllegalArgumentException("enabled must be true or false");
        String nextOwner=string(request,"ownerId",owner).strip(), nextToken=string(request,"botToken","").strip();
        boolean clear=request.has("clearToken") && bool(request,"clearToken");
        if(clear)nextToken=""; else if(nextToken.isEmpty())nextToken=token;
        if(!nextOwner.isEmpty() && (!nextOwner.matches("[1-9][0-9]{0,19}") || new java.math.BigInteger(nextOwner).bitLength()>64))throw new IllegalArgumentException("Owner ID must be a Discord user snowflake, not a username");
        if(!nextToken.isEmpty() && !nextToken.matches("[A-Za-z0-9._-]{20,512}"))throw new IllegalArgumentException("Invalid Discord bot token format");
        boolean nextEnabled=bool(request,"enabled");
        if(nextEnabled && (nextToken.isEmpty()||nextOwner.isEmpty()))throw new IllegalArgumentException("A bot token and owner Discord user ID are required");
        JsonObject config=new JsonObject();config.addProperty("enabled",nextEnabled);config.addProperty("ownerId",nextOwner);config.addProperty("botToken",nextToken);
        if(save) { TaskFiles.write(configFile,config); privateFile(configFile); }
        if(!owner.isEmpty() && !owner.equals(nextOwner)) { pending.clear(); dirty=true; } // Never send a previous owner's backlog to a new recipient.
        owner=nextOwner;token=nextToken;enabled=nextEnabled;channel="";blocked=false;retryAt=0;error="";revision++;
        if(!journalHealthy)error="Notification outbox is unreadable; restore or move discord-outbox.json before restarting. Original left untouched.";
    }
    private static String string(JsonObject input,String key,String fallback) {
        if(!input.has(key))return fallback;
        if(!input.get(key).isJsonPrimitive() || !input.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException(key+" must be text");
        return input.get(key).getAsString();
    }
    private static boolean bool(JsonObject input,String key) {
        if(!input.get(key).isJsonPrimitive() || !input.getAsJsonPrimitive(key).isBoolean())throw new IllegalArgumentException(key+" must be true or false");
        return input.get(key).getAsBoolean();
    }
    private static void privateFile(Path file) {
        try { if(Files.getFileStore(file).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------")); }
        catch(IOException e) { throw new IllegalStateException("Cannot restrict Discord settings file permissions"); }
    }
    public synchronized JsonObject status() {
        JsonObject result=new JsonObject();result.addProperty("enabled",enabled);result.addProperty("configured",!token.isEmpty()&&!owner.isEmpty());result.addProperty("ownerId",owner);
        result.addProperty("blocked",blocked||!journalHealthy);result.addProperty("pending",pending.size());result.addProperty("delivered",delivered);result.addProperty("dropped",dropped);result.addProperty("lastDeliveredAt",lastDelivered);result.addProperty("retryAt",retryAt);result.addProperty("error",error);
        return result;
    }
    public synchronized JsonObject test() {
        if(!journalHealthy)throw new IllegalStateException("Restore or move the unreadable Discord outbox before restarting");
        if(!enabled || token.isEmpty() || owner.isEmpty())throw new IllegalStateException("Save and enable Discord notifications first");
        if(pending.size()>=256)throw new IllegalStateException("Discord outbox is full; save corrected settings to resume its pending deliveries");
        blocked=false;retryAt=0;
        enqueue("Discord test", "Owner notifications are connected. Jobs, failures and safety incidents will arrive here.");
        return status();
    }

    /** Prime existing history without notifying about old finished jobs. Restart inspection is observed afterwards. */
    public synchronized void prime(JsonObject task) { observeTask(task,true); }
    public synchronized void observeTask(JsonObject task) { observeTask(task,false); }
    private void observeTask(JsonObject task,boolean prime) {
        String id=text(task,"id"), state=text(task,"status"); if(id.isEmpty())return;
        boolean runAlarm=false;
        if(task.has("runs"))for(var entry:task.getAsJsonObject("runs").entrySet()) {
            JsonObject run=entry.getValue().getAsJsonObject();String status=text(run,"status");
            if(text(run,"requestedStatus").equals("Failed"))status="Failed";
            boolean alarm=status.equals("Failed") || status.equals("Inspection required");runAlarm|=alarm;
            JsonObject progress=run.has("actionProgress")?run.getAsJsonObject("actionProgress"):null;
            boolean stalled=TaskWire.flag(run,"stallPaused");
            String stallId=run.has("stallIncidentId")?text(run,"stallIncidentId"):progress==null?"unreported":text(progress,"incidentId");
            transition("stall:"+id+":"+entry.getKey(),stalled?stallId:"",prime||!stalled,
                text(task,"name")+" · worker stalled", "Worker: "+entry.getKey()+"\nJob: "+id+"\n"+(progress==null?"":ActionProgress.describe(progress))+"\n"+text(run,"detail"));
            transition("run:"+id+":"+entry.getKey(),status,prime||!alarm,text(task,"name")+" · "+status,
                "Crew: "+text(task,"crew")+"\nWorker: "+entry.getKey()+"\nJob: "+id+"\n"+text(run,"detail"));
            String configError=text(run,"configError");
            transition("config:"+id+":"+entry.getKey(),configError.isEmpty()?"":TaskFiles.hash(configError),prime||configError.isEmpty(),text(task,"name")+" · configuration failed","Worker: "+entry.getKey()+"\nJob: "+id+"\n"+configError);
        }
        boolean notify=Set.of("Complete","Cancelled","Failed","Inspection required").contains(state) && !(runAlarm && Set.of("Failed","Inspection required").contains(state));
        transition("job:"+id,state,prime||!notify,text(task,"name")+" · "+state,"Crew: "+text(task,"crew")+"\nJob: "+id+"\n"+text(task,"detail"));
    }
    private void transition(String key,String state,boolean quiet,String title,String detail) {
        state=clip(state,100);
        if(state.equals(states.get(key)))return;
        states.remove(key);states.put(key,state);while(states.size()>2048)states.remove(states.keySet().iterator().next());dirty=true;
        if(!quiet)enqueue(title,detail);
    }
    public synchronized void incident(JsonObject event) {
        String kind=text(event,"kind");if(!Set.of("death","hostile_logout").contains(kind))return;
        String key="incident:"+text(event,"workerId")+":"+kind;
        String source=text(event,"id");if(source.equals(states.get(key)))return;
        JsonObject evidence=event.getAsJsonObject("evidence");
        String detail="Crew: "+text(event,"crew")+"\nWorker: "+text(event,"workerName")+"\n"+text(evidence,"scope")+"\n";
        if(evidence.has("x"))detail+="Position: "+text(evidence,"x")+", "+text(evidence,"y")+", "+text(evidence,"z")+"\n";
        detail+=kind.equals("hostile_logout")?"Known hostile: "+text(evidence,"name"):"Worker died. "+text(evidence,"deathMessage")+"\nInspect the incident timeline for attribution.";
        transition(key,source,false,text(event,"workerName")+" · "+kind.replace('_',' '),detail);
    }
    public synchronized void failure(String kind,String worker,String detail) {
        String key=TaskFiles.hash(kind+"\n"+worker+"\n"+detail);
        long now=System.currentTimeMillis();if(now-alerts.getOrDefault(key,0L)<300_000)return;
        alerts.remove(key);alerts.put(key,now);while(alerts.size()>1024)alerts.remove(alerts.keySet().iterator().next());dirty=true;
        enqueue(kind.replace('-',' '), (worker.isEmpty()?"":"Worker: "+worker+"\n")+detail);
    }
    public synchronized void observeWorker(JsonObject report) {
        String phase=text(report,"phase"),id=text(report,"id");
        if(id.isEmpty()||phase.isEmpty())return;
        boolean alarm=Set.of("failed","blocked","controller error / inspect job").contains(phase);
        transition("worker:"+id,phase,!alarm,text(report,"name")+" · "+phase,"Crew: "+text(report,"crew")+"\n"+text(report,"scope")+"\nPosition: "+text(report,"x")+", "+text(report,"y")+", "+text(report,"z")+"\n"+text(report,"status"));
    }
    private void enqueue(String title,String detail) {
        if(!enabled || closed)return;
        if(pending.size()>=256){dropped++;error="Discord outbox is full; a notification was dropped. Fix delivery before further alerts are lost.";return;}
        JsonObject item=new JsonObject();item.addProperty("id",TaskFiles.hash(UUID.randomUUID().toString()).substring(0,24));
        String content="[Monocle] "+title+"\n"+detail+"\n"+Instant.now();
        content=content.replace('`','\'').replace('@','＠').replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","");
        if(!token.isEmpty())content=content.replace(token,"[redacted]");
        content=clip(content,1900);
        item.addProperty("content",content);item.addProperty("attempts",0);pending.addLast(item);dirty=true;
    }
    private static String clip(String text,int limit){return text.length()<=limit?text:text.substring(0,Character.isHighSurrogate(text.charAt(limit-1))?limit-1:limit);}
    private JsonObject journalSnapshot() {
            JsonObject saved=new JsonObject();saved.addProperty("version",1);saved.addProperty("ownerId",owner);saved.add("states",new Gson().toJsonTree(states));saved.add("alerts",new Gson().toJsonTree(alerts));saved.add("pending",new Gson().toJsonTree(pending));
            return saved;
    }
    private boolean checkpoint() {
        synchronized(journalLock) {
            JsonObject saved;
            synchronized(this){if(!journalHealthy)return false;if(!dirty)return true;saved=journalSnapshot();}
            try {
                TaskFiles.write(journal,saved);privateFile(journal);
                synchronized(this){JsonArray queue=saved.getAsJsonArray("pending");persistedHead=queue.isEmpty()?"":text(queue.get(0).getAsJsonObject(),"id");if(saved.equals(journalSnapshot()))dirty=false;}
                return true;
            } catch(RuntimeException e) { synchronized(this){error="Cannot save Discord outbox; delivery held until the journal can be saved.";}return false; }
        }
    }
    private void pump() {
        try {
            if(!checkpoint())return;
            JsonObject item;String auth,recipient,dm;long version;
            synchronized(this) {
                if(closed || !enabled || blocked || pending.isEmpty() || System.currentTimeMillis()<retryAt)return;
                if(!persistedHead.equals(text(pending.getFirst(),"id")))return;
                item=pending.getFirst().deepCopy();auth=token;recipient=owner;dm=channel;version=revision;
            }
            if(dm.isEmpty()) {
                JsonObject body=new JsonObject();body.addProperty("recipient_id",recipient);
                Response response=request("users/@me/channels",body,auth);
                if(!accepted(response,version,item))return;
                dm=text(response.body,"id");if(!dm.matches("[1-9][0-9]{0,19}"))throw new IOException("Invalid DM channel response");
                synchronized(this){if(version!=revision)return;channel=dm;}
            }
            JsonObject body=new JsonObject();body.addProperty("content",text(item,"content"));body.addProperty("nonce",text(item,"id"));body.addProperty("enforce_nonce",true);
            JsonObject mentions=new JsonObject();mentions.add("parse",new JsonArray());body.add("allowed_mentions",mentions);
            Response response=request("channels/"+dm+"/messages",body,auth);
            if(response.code>=200&&response.code<300&&!text(response.body,"id").matches("[1-9][0-9]{0,19}"))throw new IOException("Invalid message response");
            if(!accepted(response,version,item))return;
            synchronized(this) {
                if(version!=revision)return;
                pending.removeFirst();delivered++;lastDelivered=System.currentTimeMillis();error="";dirty=true;
                retryAt=System.currentTimeMillis()+Math.max(1000,response.waitMillis);
            }
            checkpoint();
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(Exception e) { synchronized(this){if(!closed){error="Discord network/response error; retrying. No credentials were logged.";retryAt=System.currentTimeMillis()+30_000;}} }
    }
    private record Response(int code,JsonObject body,long waitMillis) { }
    private Response request(String path,JsonObject body,String auth) throws IOException,InterruptedException,ExecutionException,TimeoutException {
        HttpRequest request=HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bot "+auth)
            .header("User-Agent","DiscordBot (https://github.com/exec/monocle-client, 0.14.3)").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        // Include body arrival in the deadline; an InputStream handler only waits for headers.
        var call=http.sendAsync(request,_ -> new LimitedBody());
        HttpResponse<byte[]> response;
        try {response=call.get(10,TimeUnit.SECONDS);}finally{call.cancel(true);}
        JsonObject json=new JsonObject();
        if(response.body().length>0)try {json=JsonParser.parseString(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException ignored){ }
        long wait=0;
        if(response.statusCode()==429)wait=seconds(json.has("retry_after")?json.get("retry_after").getAsString():response.headers().firstValue("Retry-After").orElse("30"));
        else if(response.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0"))wait=seconds(response.headers().firstValue("X-RateLimit-Reset-After").orElse("1"));
        return new Response(response.statusCode(),json,wait);
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>32768-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("Discord response too large"));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    private static long seconds(String text) { try {double value=Double.parseDouble(text);return Double.isFinite(value)?(long)Math.max(1000,Math.min(86_400_000,Math.ceil(value*1000))):30_000;}catch(NumberFormatException e){return 30_000;} }
    private synchronized boolean accepted(Response response,long version,JsonObject item) {
        if(version!=revision || closed)return false;
        if(response.code>=200 && response.code<300){retryAt=System.currentTimeMillis()+response.waitMillis;return true;}
        error="Discord HTTP "+response.code+": "+(response.code==401?"invalid bot token":response.code==403?"owner DM unavailable; check bot access/privacy":response.code==429?"rate limited; waiting":"delivery failed");
        if(response.code==429)retryAt=System.currentTimeMillis()+Math.max(1000,response.waitMillis);
        else if(response.code>=500) {
            int attempts=integer(item,"attempts",0,100000)+1;pending.getFirst().addProperty("attempts",Math.min(attempts,100000));dirty=true;
            retryAt=System.currentTimeMillis()+Math.min(300_000,5000L*(1L<<Math.min(attempts,6)));
        } else { blocked=true;channel=""; }
        return false;
    }
    @Override public void close() { synchronized(this){if(closed)return;closed=true;}sender.shutdownNow();http.shutdownNow();checkpoint(); }
}
