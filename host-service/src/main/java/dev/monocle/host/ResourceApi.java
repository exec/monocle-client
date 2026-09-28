package dev.monocle.host;

import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import dev.monocle.coordinator.TaskFiles;
import static dev.monocle.coordinator.TaskWire.text;

/** Draft v1 operator resources. All decisions and persistence remain in HostService.control. */
final class ResourceApi {
    record Reply(int code, JsonObject body) {}
    static final class Precondition extends RuntimeException {
        final int status;
        Precondition(int status, String message) { super(message); this.status = status; }
    }
    private ResourceApi() {}
    static boolean bodyRequired(String method) { return Set.of("POST", "PUT", "PATCH").contains(method); }

    static Reply route(HostService host, String method, URI uri, JsonObject body, String ifMatch) {
        String path = uri.getPath();
        if (!uri.getRawPath().equals(path) || !path.startsWith("/v1/")) throw new IllegalArgumentException("Encoded resource paths are not supported");
        String[] parts = path.split("/", -1);
        if (parts.length < 3 || parts.length > 7 || !parts[1].equals("v1") || Arrays.stream(parts).skip(2).anyMatch(String::isBlank)) return missing();
        Map<String,String> query = query(uri);
        String resource = parts[2];
        if (method.equals("GET")) {
            if (parts.length == 4 && resource.equals("operations") && query.isEmpty()) {
                JsonObject operation = host.operatorOperations().get(parts[3]);
                if (operation == null) return missing();
                operation.remove("fingerprint");
                return new Reply(200, operation);
            }
            if (parts.length == 3 && resource.equals("events")) {
                if (query.keySet().stream().anyMatch(key -> !Set.of("cursor", "limit").contains(key))) throw new IllegalArgumentException("Unsupported event filter");
                int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 100;
                JsonObject feed = host.operatorEvents(query.get("cursor"), limit);
                return new Reply(feed.has("snapshotRequired") ? 409 : 200, feed);
            }
        }
        JsonObject snapshot = host.control(op("status"));
        if (method.equals("GET")) {
            if (parts.length == 3 && Set.of("host", "health", "capabilities", "configuration-controls").contains(resource)) {
                if (!query.isEmpty()) throw new IllegalArgumentException("Unexpected query parameter");
                if(resource.equals("configuration-controls"))return new Reply(200,host.control(op("configuration-controls")));
                JsonObject result = new JsonObject();
                switch (resource) {
                    case "host" -> { result.addProperty("apiVersion", "workers.monocle.dev/v1-draft"); result.addProperty("status", text(snapshot,"status")); result.addProperty("operationsVersion", snapshot.get("operationsVersion").getAsInt()); }
                    case "health" -> { for (String key : List.of("status", "lastConnectionError", "telemetryError", "telemetryDropped", "rosterError")) result.add(key, snapshot.get(key).deepCopy()); }
                    default -> {
                        result.add("actions", snapshot.getAsJsonArray("capabilities").deepCopy());
                        result.add("portableActions", new Gson().toJsonTree(List.of("workers.wait.v1", "workers.travel.v1", "workers.drop-items.v1")));
                    }
                }
                return new Reply(200, result);
            }
            JsonArray items = items(snapshot, resource);
            if (items == null) return missing();
            if (parts.length == 3) return new Reply(200, page(items, query, resource));
            if(parts.length==5 && query.isEmpty() && resource.equals("workers") && parts[4].equals("resources")) {
                if(find(items,parts[3])==null)return missing();
                return new Reply(200,workerResources(snapshot,parts[3]));
            }
            if(parts.length==5 && query.isEmpty() && resource.equals("stashes") && parts[4].equals("resources")) {
                JsonObject stash=find(items,parts[3]);if(stash==null)return missing();
                JsonObject result=new JsonObject();result.addProperty("stashId",parts[3]);result.add("items",stash.getAsJsonObject("items").deepCopy());
                for(String key:List.of("observed","inferred","unscanned","observedAt"))if(stash.has(key))result.add(key,stash.get(key).deepCopy());
                result.addProperty("complete",(!stash.has("unscanned") || stash.get("unscanned").getAsInt()==0)
                    && (!stash.has("inferred") || stash.get("inferred").getAsInt()==0));
                return new Reply(200,result);
            }
            if(parts.length==5 && query.isEmpty() && resource.equals("jobs") && parts[4].equals("configuration")) {
                if(find(items,parts[3])==null)return missing();
                return new Reply(200,jobConfiguration(host,parts[3]));
            }
            if (parts.length != 4 || !query.isEmpty()) return missing();
            JsonObject item = find(items, parts[3]);
            if (item == null) return missing();
            if (resource.equals("jobs")) return new Reply(200, job(host.control(withId("task-get", parts[3]))));
            if (resource.equals("workflows")) return new Reply(200, workflow(host.control(withId("workflow-get", workflowKey(snapshot, parts[3])))));
            if (resource.equals("drafts")) return new Reply(200, host.control(withId("draft-get", parts[3])));
            if (resource.equals("stashes")) {
                JsonObject request = op("stash-get");
                for (String key : List.of("crew", "scope", "name")) request.add(key, item.get(key).deepCopy());
                return new Reply(200, stash(host.control(request)));
            }
            return new Reply(200, item);
        }
        if (!query.isEmpty()) throw new IllegalArgumentException("Mutation routes do not accept query parameters");
        if (resource.equals("workers") && parts.length==4 && method.equals("DELETE")) {
            if(find(items(snapshot,"workers"),parts[3])==null)return missing();
            JsonObject request=op("worker-forget");request.addProperty("worker",uuid(parts[3]));host.control(request);
            return new Reply(200,receipt(parts[3],"Forgotten"));
        }
        if (resource.equals("crews")) return crews(host, snapshot, method, parts, body, ifMatch);
        if (resource.equals("jobs")) return jobs(host, snapshot, method, parts, body);
        if (resource.equals("workflows")) return workflows(host, snapshot, method, parts, body, ifMatch);
        if (resource.equals("drafts")) return drafts(host, snapshot, method, parts, body);
        return missing();
    }

    private static Reply crews(HostService host, JsonObject snapshot, String method, String[] parts, JsonObject body, String ifMatch) {
        if (parts.length == 3 && method.equals("POST")) {
            String id = body.has("id") ? uuid(text(body,"id")) : UUID.randomUUID().toString();
            JsonObject request = withId("crew-create", id); request.addProperty("name", text(body,"name"));
            return new Reply(201, crew(host.control(request), "crew-" + id));
        }
        if (parts.length < 4) return missing();
        String internal = crewKey(snapshot, parts[3]);
        if (parts.length == 4 && method.equals("PATCH")) {
            checkRevision(crew(snapshot, internal), ifMatch);
            JsonObject request=op("crew-rename");request.addProperty("crew",internal);request.addProperty("name",text(body,"name"));
            return new Reply(200,crew(host.control(request),internal));
        }
        if (parts.length == 4 && method.equals("DELETE")) {
            JsonObject request=op("crew-delete");request.addProperty("crew",internal);host.control(request);
            return new Reply(200,receipt(parts[3],"Deleted"));
        }
        if (parts.length == 6 && parts[4].equals("workers") && method.equals("PUT")) {
            String worker=uuid(parts[5]);
            if (find(items(snapshot,"workers"),worker)==null) return missing();
            JsonObject request=op("crew-move");request.addProperty("crew",internal);request.addProperty("worker",worker);host.control(request);
            return new Reply(200,receipt(worker,"Reassignment sent; confirm after worker reconnects"));
        }
        return missing();
    }

    private static Reply jobs(HostService host, JsonObject snapshot, String method, String[] parts, JsonObject body) {
        if (parts.length == 3 && method.equals("POST")) {
            String id=uuid(text(body,"id")), crew=crewKey(snapshot,text(body,"crewId"));
            JsonObject scope=object(body,"scope");
            String server=text(scope,"server"),dimension=text(scope,"dimension");
            int sources=(body.has("workflowId")?1:0)+(body.has("package")?1:0)+(body.has("action")?1:0);
            if(sources!=1)throw new IllegalArgumentException("Specify exactly one action, workflowId, or captured package");
            JsonObject request=withId("submit",id);
            request.addProperty("crew",crew);request.addProperty("name",body.has("name")?text(body,"name"):body.has("action")?"Portable action":"");
            request.addProperty("server",server);request.addProperty("dimension",dimension);
            if(!body.has("workerIds") || !body.get("workerIds").isJsonArray())throw new IllegalArgumentException("Specify workerIds");
            request.add("workers",body.getAsJsonArray("workerIds"));
            if(body.has("priority"))request.add("priority",body.get("priority"));
            JsonObject args=body.has("action")?portableArgs(body,server,dimension):body.has("args")?body.getAsJsonObject("args"):new JsonObject();request.add("args",args);
            if(body.has("workflowId") || body.has("action")) {
                String workflow=body.has("action")?portableWorkflow(object(body,"action")):workflowKey(snapshot,text(body,"workflowId"));
                JsonObject prepare=op("workflow-prepare");prepare.addProperty("id",workflow);
                prepare.addProperty("scope",server+"\n"+dimension);prepare.add("args",args);
                request.add("package",host.control(prepare));
            } else request.add("package",body.get("package"));
            host.control(request);
            return new Reply(201,job(host.control(withId("task-get",id))));
        }
        if(parts.length<4)return missing();
        String id=uuid(parts[3]);
        if(find(items(snapshot,"jobs"),id)==null)return missing();
        if(parts.length==5 && method.equals("PATCH") && parts[4].equals("configuration")) {
            JsonObject preview=op("preview-configuration");
            preview.addProperty("control",text(body,"control"));
            if(body.has("active"))preview.add("active",body.get("active"));
            if(body.has("value"))preview.add("value",body.get("value"));
            JsonObject modules=host.control(preview).getAsJsonObject("modules");
            JsonObject request=withId("configure",id);request.add("modules",modules);
            if(body.has("workerId"))request.addProperty("worker",uuid(text(body,"workerId")));
            host.control(request);return new Reply(200,jobConfiguration(host,id));
        }
        if(parts.length==4 && method.equals("DELETE")) {host.control(withId("delete",id));return new Reply(200,receipt(id,"Deleted"));}
        if(parts.length==4 && method.equals("PATCH")) {
            JsonObject request=withId("priority",id);request.add("priority",body.get("priority"));
            host.control(request);return new Reply(200,job(host.control(withId("task-get",id))));
        }
        if(parts.length==5 && method.equals("POST") && Set.of("pause","resume","cancel","release").contains(parts[4])) {
            JsonObject request=withId(parts[4].equals("release")?"task-release":parts[4],id);
            if(parts[4].equals("release"))request.addProperty("newId",uuid(text(body,"newId")));
            return new Reply(200,host.control(request));
        }
        if(parts.length==7 && parts[4].equals("workers") && method.equals("POST") && Set.of("detach","rejoin").contains(parts[6])) {
            JsonObject request=withId("detach",id);request.addProperty("worker",uuid(parts[5]));request.addProperty("detached",parts[6].equals("detach"));
            return new Reply(200,host.control(request));
        }
        return missing();
    }

    private static String portableWorkflow(JsonObject action) {
        if(action==null)throw new IllegalArgumentException("Specify a typed action");
        return switch(string(action,"type")) {
            case "workers.wait.v1" -> "task-wait";
            case "workers.travel.v1" -> "task-travel";
            case "workers.drop-items.v1" -> "task-drop";
            default -> throw new IllegalArgumentException("Unsupported portable action");
        };
    }
    private static JsonObject portableArgs(JsonObject submission,String server,String dimension) {
        if(submission.has("args"))throw new IllegalArgumentException("Portable actions use action.arguments, not job args");
        JsonObject action=object(submission,"action");
        String workflow=portableWorkflow(action);
        JsonObject source=object(action,"arguments");
        JsonObject args=new JsonObject();
        switch(workflow) {
            case "task-wait" -> args.addProperty("ticks",whole(source,"ticks",0,1_728_000));
            case "task-travel" -> {
                JsonObject world=object(source,"scope");
                if(!server.equals(string(world,"server")) || !dimension.equals(string(world,"dimension")))
                    throw new IllegalArgumentException("Travel scope must match the job world");
                args.addProperty("x",real(source,"x",-29_999_984,29_999_984));
                args.addProperty("y",real(source,"y",-2048,2048));
                args.addProperty("z",real(source,"z",-29_999_984,29_999_984));
                if(source.has("radius"))args.addProperty("radius",real(source,"radius",.15,8));
            }
            case "task-drop" -> {
                String item=string(source,"item");
                if(!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || item.length()>128)throw new IllegalArgumentException("Invalid item ID");
                args.addProperty("item",item);args.addProperty("count",whole(source,"count",1,1_048_576));
                if(source.has("recipientWorkerId"))args.addProperty("recipient",uuid(string(source,"recipientWorkerId")));
            }
            default -> throw new AssertionError(workflow);
        }
        return args;
    }
    private static String string(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Expected string "+key);
        return value.getAsString();
    }
    private static JsonObject object(JsonObject source,String key) {
        JsonElement value=source.get(key);
        if(value==null || !value.isJsonObject())throw new IllegalArgumentException("Expected object "+key);
        return value.getAsJsonObject();
    }
    private static int whole(JsonObject object,String key,int min,int max) {
        JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected integer "+key);
        int number;
        try {number=value.getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Expected integer "+key);}
        if(number<min || number>max)throw new IllegalArgumentException(key+" must be "+min+"–"+max);
        return number;
    }
    private static double real(JsonObject object,String key,double min,double max) {
        JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected number "+key);
        double number=value.getAsDouble();
        if(!Double.isFinite(number) || number<min || number>max)throw new IllegalArgumentException(key+" must be "+min+"–"+max);
        return number;
    }

    private static Reply workflows(HostService host, JsonObject snapshot, String method, String[] parts, JsonObject body, String ifMatch) {
        if(parts.length==3 && method.equals("POST")) {
            String id=body.has("id")?uuid(text(body,"id")):UUID.randomUUID().toString();
            return new Reply(201,saveWorkflow(host,id,body));
        }
        if(parts.length!=4)return missing();
        String key=workflowKey(snapshot,parts[3]);
        if(method.equals("PUT")) {
            checkRevision(workflow(host.control(withId("workflow-get", key))), ifMatch);
            return new Reply(200,saveWorkflow(host,key,body));
        }
        if(method.equals("DELETE")) {host.control(withId("workflow-delete",key));return new Reply(200,receipt(parts[3],"Deleted"));}
        return missing();
    }
    private static JsonObject saveWorkflow(HostService host,String id,JsonObject body) {
        JsonObject request=withId("workflow-save",id);request.addProperty("name",text(body,"name"));request.addProperty("folder",text(body,"folder"));
        request.add("package",body.getAsJsonObject("package"));return workflow(host.control(request));
    }
    private static Reply drafts(HostService host,JsonObject snapshot,String method,String[] parts,JsonObject body) {
        if(parts.length==3 && method.equals("POST")) {
            JsonObject request=op("draft-save");request.add("draft",body);
            return new Reply(201,host.control(request));
        }
        if(parts.length<4 || find(items(snapshot,"drafts"),parts[3])==null)return missing();
        String id=uuid(parts[3]);
        if(parts.length==4 && method.equals("DELETE")) {
            host.control(withId("draft-delete",id));return new Reply(200,receipt(id,"Deleted"));
        }
        if(parts.length==5 && parts[4].equals("dispatch") && method.equals("POST")) {
            JsonObject request=withId("draft-assign",id);request.addProperty("crew",crewKey(snapshot,text(body,"crewId")));
            request.add("workers",body.getAsJsonArray("workerIds"));host.control(request);
            return new Reply(201,job(host.control(withId("task-get",id))));
        }
        return missing();
    }

    private static JsonArray items(JsonObject snapshot,String resource) {
        JsonArray source;JsonArray result=new JsonArray();
        switch(resource) {
            case "workers" -> source=snapshot.getAsJsonArray("workers");
            case "crews" -> {
                for(JsonElement value:snapshot.getAsJsonArray("crews"))result.add(crew(snapshot,value.getAsString()));
                return result;
            }
            case "jobs" -> {
                for(String key:List.of("tasks","history"))for(JsonElement value:snapshot.getAsJsonArray(key))result.add(job(value.getAsJsonObject()));
                return result;
            }
            case "workflows" -> source=snapshot.getAsJsonArray("workflows");
            case "drafts" -> source=snapshot.getAsJsonArray("drafts");
            case "stashes" -> source=snapshot.getAsJsonArray("stashes");
            default -> {return null;}
        }
        for(JsonElement value:source)result.add(switch(resource) {
            case "workers" -> worker(value.getAsJsonObject(),snapshot);
            case "workflows" -> workflow(value.getAsJsonObject());
            case "drafts" -> value.getAsJsonObject().deepCopy();
            default -> stash(value.getAsJsonObject());
        });
        return result;
    }
    private static JsonObject worker(JsonObject source,JsonObject snapshot) {
        JsonObject view=source.deepCopy();view.addProperty("crewId",publicCrew(text(source,"crew")));
        view.addProperty("connection",!source.get("connected").getAsBoolean()?"offline":source.get("reconciled").getAsBoolean()?"online":"reconciling");
        view.addProperty("observedAt",Instant.ofEpochMilli(source.get("lastSeen").getAsLong()).toString());
        view.add("capabilities",snapshot.getAsJsonArray("capabilities").deepCopy());return view;
    }
    private static JsonObject crew(JsonObject snapshot,String internal) {
        JsonObject view=new JsonObject();view.addProperty("id",publicCrew(internal));
        String label=text(snapshot.getAsJsonObject("crewLabels"),internal);view.addProperty("name",label.isBlank()?internal:label);
        view.addProperty("revision", TaskFiles.hash(internal + "\n" + text(view,"name")));
        JsonArray workers=new JsonArray(),jobs=new JsonArray();
        for(JsonElement value:snapshot.getAsJsonArray("workers"))if(text(value.getAsJsonObject(),"crew").equals(internal))workers.add(value.getAsJsonObject().get("id").deepCopy());
        for(JsonElement value:snapshot.getAsJsonArray("tasks"))if(text(value.getAsJsonObject(),"crew").equals(internal))jobs.add(value.getAsJsonObject().get("id").deepCopy());
        view.add("workerIds",workers);view.add("jobIds",jobs);
        JsonObject highway=snapshot.getAsJsonObject("highways").getAsJsonObject(internal);
        if(highway!=null&&highway.has("resourceCounts")&&highway.getAsJsonObject("resourceCounts").get("workers").getAsInt()>0) {
            JsonObject counts=highway.getAsJsonObject("resourceCounts").deepCopy();
            counts.getAsJsonObject("total").add("pavingBlocks",counts.getAsJsonObject("total").remove("obsidian"));
            view.add("highwayResourceCounts",counts);
        }
        return view;
    }
    private static JsonObject job(JsonObject source) {
        JsonObject view=source.deepCopy();view.addProperty("crewId",publicCrew(text(source,"crew")));
        view.addProperty("state",text(source,"status").toLowerCase(Locale.ROOT).replace(' ','_'));
        JsonObject scope=new JsonObject();scope.addProperty("server",text(source,"server"));scope.addProperty("dimension",text(source,"dimension"));view.add("scope",scope);
        JsonArray workers=new JsonArray();source.getAsJsonObject("runs").keySet().forEach(workers::add);view.add("workerIds",workers);
        return view;
    }
    private static JsonObject workflow(JsonObject source) {
        JsonObject view=source.deepCopy();view.addProperty("id",publicWorkflow(text(source,"id")));
        view.addProperty("revision", TaskFiles.hash(text(source,"id") + "\n" + text(source,"name") + "\n" + text(source,"folder") + "\n" + source.get("package")));
        return view;
    }
    private static void checkRevision(JsonObject current, String ifMatch) {
        if (ifMatch == null) throw new Precondition(428, "If-Match revision is required");
        if (!ifMatch.equals(text(current,"revision"))) throw new Precondition(412, "Resource revision changed; refresh before editing");
    }
    private static JsonObject stash(JsonObject source) {
        JsonObject view=source.deepCopy();view.addProperty("id",stable("stash",text(source,"crew")+"\n"+text(source,"scope")+"\n"+text(source,"name")));
        view.addProperty("crewId",publicCrew(text(source,"crew")));
        String[] scope=text(source,"scope").split("\n",2);
        if(scope.length==2){JsonObject world=new JsonObject();world.addProperty("server",scope[0]);world.addProperty("dimension",scope[1]);view.add("world",world);}
        if(view.has("updatedAt"))view.addProperty("observedAt",Instant.ofEpochMilli(view.get("updatedAt").getAsLong()).toString());
        return view;
    }
    private static JsonObject jobConfiguration(HostService host,String id) {
        JsonObject view=host.control(withId("task-configuration",id));view.addProperty("jobId",id);return view;
    }
    private static JsonObject workerResources(JsonObject snapshot,String id) {
        JsonObject result=new JsonObject();result.addProperty("workerId",id);result.addProperty("known",false);
        for(var highway:snapshot.getAsJsonObject("highways").entrySet()) {
            JsonObject state=highway.getValue().getAsJsonObject();if(!state.has("workers"))continue;
            for(JsonElement value:state.getAsJsonArray("workers")) {
                JsonObject worker=value.getAsJsonObject();
                if(!id.equals(text(worker,"id")) || !worker.has("resourceCounts") || !worker.get("fresh").getAsBoolean())continue;
                JsonObject counts=worker.getAsJsonObject("resourceCounts").deepCopy();
                for(String tier:List.of("inventory","enderChest","total")) {
                    JsonObject values=counts.getAsJsonObject(tier);values.add("pavingBlocks",values.remove("obsidian"));
                }
                result.addProperty("known",true);result.addProperty("source","highway-worker-report");result.addProperty("jobId",text(state,"execution"));
                result.addProperty("observedAt",Instant.ofEpochMilli(System.currentTimeMillis()-worker.get("reportAgeMs").getAsLong()).toString());
                result.add("counts",counts);return result;
            }
        }
        return result;
    }
    private static JsonObject page(JsonArray source,Map<String,String> query,String resource) {
        Set<String> allowed=Set.of("limit","cursor","crewId","status","connected");
        if(query.keySet().stream().anyMatch(k->!allowed.contains(k)) || (query.containsKey("crewId")&&!Set.of("workers","jobs","stashes").contains(resource))
            || (query.containsKey("status")&&!resource.equals("jobs")) || (query.containsKey("connected")&&!resource.equals("workers")))throw new IllegalArgumentException("Unsupported filter");
        if(query.containsKey("crewId"))uuid(query.get("crewId"));
        if(query.containsKey("connected")&&!Set.of("true","false").contains(query.get("connected")))throw new IllegalArgumentException("connected must be true or false");
        int limit=query.containsKey("limit")?Integer.parseInt(query.get("limit")):50;
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1–100");
        List<JsonObject> rows=new ArrayList<>();
        for(JsonElement value:source) {
            JsonObject item=value.getAsJsonObject();
            if(query.containsKey("crewId")&&!query.get("crewId").equals(text(item,"crewId")))continue;
            if(query.containsKey("status")&&!query.get("status").equalsIgnoreCase(text(item,"status")))continue;
            if(query.containsKey("connected")&&!query.get("connected").equals(Boolean.toString(item.get("connected").getAsBoolean())))continue;
            rows.add(item);
        }
        rows.sort(Comparator.comparing(r->text(r,"id")));
        int start=0;
        if(query.containsKey("cursor")) {
            try {start=Integer.parseInt(new String(Base64.getUrlDecoder().decode(query.get("cursor")),StandardCharsets.US_ASCII));}
            catch(RuntimeException e){throw new IllegalArgumentException("Invalid cursor");}
            if(start<0||start>rows.size())throw new IllegalArgumentException("Invalid cursor");
        }
        JsonArray page=new JsonArray();for(int i=start;i<Math.min(rows.size(),start+limit);i++)page.add(rows.get(i));
        JsonObject result=new JsonObject();result.add("items",page);
        if(start+limit<rows.size())result.addProperty("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(start+limit).getBytes(StandardCharsets.US_ASCII)));
        return result;
    }
    private static Map<String,String> query(URI uri) {
        Map<String,String> result=new HashMap<>();String raw=uri.getRawQuery();if(raw==null)return result;
        for(String part:raw.split("&",-1)) {
            String[] pair=part.split("=",2);
            if(pair.length!=2||pair[0].isBlank())throw new IllegalArgumentException("Invalid query parameter");
            String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
            if(result.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("Duplicate query parameter");
        }
        return result;
    }
    private static JsonObject find(JsonArray items,String id) {
        uuid(id);for(JsonElement value:items)if(text(value.getAsJsonObject(),"id").equals(id))return value.getAsJsonObject();return null;
    }
    private static String crewKey(JsonObject snapshot,String publicId) {
        uuid(publicId);for(JsonElement value:snapshot.getAsJsonArray("crews"))if(publicCrew(value.getAsString()).equals(publicId))return value.getAsString();
        throw new IllegalArgumentException("Unknown crew");
    }
    private static String workflowKey(JsonObject snapshot,String publicId) {
        uuid(publicId);for(JsonElement value:snapshot.getAsJsonArray("workflows"))if(publicWorkflow(text(value.getAsJsonObject(),"id")).equals(publicId))return text(value.getAsJsonObject(),"id");
        throw new IllegalArgumentException("Unknown workflow");
    }
    private static String publicCrew(String id) {
        if(id.startsWith("crew-"))try{return uuid(id.substring(5));}catch(IllegalArgumentException ignored){}
        return stable("crew",id);
    }
    private static String publicWorkflow(String id) {try{return uuid(id);}catch(IllegalArgumentException ignored){return stable("workflow",id);}}
    private static String stable(String type,String id) {return UUID.nameUUIDFromBytes(("workers.monocle.dev/v1/"+type+"/"+id).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String uuid(String value) {return UUID.fromString(value).toString();}
    private static JsonObject op(String name) {JsonObject result=new JsonObject();result.addProperty("op",name);return result;}
    private static JsonObject withId(String name,String id) {JsonObject result=op(name);result.addProperty("id",id);return result;}
    private static JsonObject receipt(String id,String status) {JsonObject result=new JsonObject();result.addProperty("id",id);result.addProperty("status",status);return result;}
    private static Reply missing() {JsonObject result=new JsonObject();result.addProperty("error","Resource not found");return new Reply(404,result);}
}
