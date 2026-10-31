package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

/** A durable, bounded batch barrier. Inventory moves remain the existing native stash actions. */
public final class StashTransferTeam {
    public static final String WORKFLOW="task-stash-transfer-team";
    public static final List<String> ACTIONS=List.of("Wait","KitTransferPlan","StashVisit","StashResupply","StashDeposit","Tpa");
    private StashTransferTeam() {}
    public static JsonObject prepare(Path root,String crew,String scope,JsonObject request,List<String> workers){
        if(workers.size()<2||workers.size()>3||new HashSet<>(workers).size()!=workers.size())throw new IllegalArgumentException("Crew stash transfer needs two or three distinct workers");
        if(request.has("targetDimension")&&!text(request,"targetDimension").equals(scope.substring(scope.indexOf('\n')+1)))throw new IllegalArgumentException("Cross-dimension stash transfers currently require a solo courier");
        if(flag(request,"depositCarried"))throw new IllegalArgumentException("Deposit carried kits separately before a crew transfer");
        if(flag(request,"depositOnly"))throw new IllegalArgumentException("Deposit-only recovery uses one worker before the crew transfer");
        if(flag(request,"enderTransport"))throw new IllegalArgumentException("Ender transport currently uses a solo courier");
        String leader=request.has("leader")?UUID.fromString(text(request,"leader")).toString():workers.getFirst();
        if(!workers.contains(leader))throw new IllegalArgumentException("The stash leader must be one of the selected workers");
        JsonObject args=StashCatalog.kitTransfer(root,crew,scope,request,leader);args.addProperty("leader",leader);
        args.addProperty("tpaWarmupTicks",request.has("tpaWarmupTicks")?StashCatalog.integer(request,"tpaWarmupTicks",0,72_000):300);
        return args;
    }
    public static JsonObject checkedRequest(JsonObject action){
        if(action.size()!=3||!text(action,"type").equals("KitTransferPlan")||!action.has("receipt")||!action.get("receipt").isJsonObject())throw new IllegalArgumentException("Crew planning accepts only type, step and receipt");
        StashCatalog.integer(action,"step",0,1_000_000);
        JsonObject r=action.getAsJsonObject("receipt");
        if(r.size()>8||!Set.of("ok","picked","nextPick","sourceExhausted","sourceIssue","deposited","missing").containsAll(r.keySet()))throw new IllegalArgumentException("Invalid crew transfer receipt");
        if(action.get("step").getAsInt()==0&&!r.isEmpty()||action.get("step").getAsInt()>0&&!r.has("ok"))throw new IllegalArgumentException("Crew step needs its previous command receipt");
        for(String key:List.of("ok","sourceExhausted"))if(r.has(key)&&(!r.get(key).isJsonPrimitive()||!r.getAsJsonPrimitive(key).isBoolean()))throw new IllegalArgumentException("Invalid "+key);
        if(r.has("picked"))StashCatalog.integer(r,"picked",0,36);
        for(String key:List.of("deposited","missing"))if(r.has(key))StashCatalog.integer(r,key,0,36);
        if(r.has("nextPick"))StashCatalog.integer(r,"nextPick",0,1024);
        if(r.has("sourceIssue")&&(!r.get("sourceIssue").isJsonPrimitive()||!r.getAsJsonPrimitive("sourceIssue").isString()||text(r,"sourceIssue").length()>1024))throw new IllegalArgumentException("Invalid source issue");
        return action.deepCopy();
    }
    /** Null means a visible barrier wait, not a completed or failed action. Adapters persist mutations before replying. */
    public static JsonObject next(Path root,JsonObject task,JsonObject run,String worker,Map<UUID,PlayerObservation> observations,long now,long clock){
        if(!text(task.getAsJsonObject("package"),"entry").equals(WORKFLOW)||!task.getAsJsonObject("runs").has(worker))throw new IllegalArgumentException("Not a crew stash assignment");
        JsonObject request=checkedRequest(run.getAsJsonObject("action"));
        if(flag(task,"cancelled")||flag(task,"paused"))return null;
        if(text(run,"kitPlanToken").equals(text(run,"token"))){if(!request.equals(run.get("kitPlanRequest")))throw new IllegalArgumentException("Crew step changed under its action token");return run.getAsJsonObject("kitPlan").deepCopy();}
        JsonObject args=task.getAsJsonObject("args");List<String> ids=List.copyOf(task.getAsJsonObject("runs").keySet());
        if(ids.size()<2||ids.size()>3||!ids.contains(text(args,"leader")))throw new IllegalArgumentException("Invalid crew transfer roster");
        for(var e:task.getAsJsonObject("runs").entrySet()){
            String status=text(e.getValue().getAsJsonObject(),"status");
            if(Set.of("Failed","Cancelled").contains(status))throw new IllegalStateException("Crew transfer held: worker "+e.getKey()+" needs attention; carried kits retained");
            if(Set.of("Suspending","Suspended","Inspection required").contains(status)){run.addProperty("detail","Crew stash transfer · waiting for worker checkpoint reconciliation");return null;}
        }
        if(!task.has("stashTeam")){
            JsonObject team=new JsonObject(),members=new JsonObject();team.addProperty("index",0);team.addProperty("phase","travel-source");team.addProperty("phaseAt",clock);team.addProperty("delivered",0);team.add("byKit",new JsonObject());
            for(String id:ids){JsonObject m=new JsonObject();m.addProperty("step",-1);m.addProperty("readyStep",-1);m.addProperty("cursor",0);m.addProperty("emptyPasses",0);m.addProperty("batch",0);m.addProperty("delivered",0);members.add(id,m);}
            team.add("members",members);planType(root,task,team,ids);task.add("stashTeam",team);
        }
        JsonObject team=task.getAsJsonObject("stashTeam"),members=team.getAsJsonObject("members"),member=members.getAsJsonObject(worker);
        int step=request.get("step").getAsInt(),last=member.get("step").getAsInt();
        if(step==last){if(!request.equals(member.get("request")))throw new IllegalArgumentException("Crew step receipt changed during retry");return reply(run,request,member.getAsJsonObject("reply"));}
        if(step!=last+1)throw new IllegalArgumentException("Crew transfer step has a gap or is stale");
        if(step>0&&member.get("readyStep").getAsInt()!=step){
            JsonObject receipt=request.getAsJsonObject("receipt"),command=member.getAsJsonObject("reply").getAsJsonObject("action");
            if(!flag(receipt,"ok")){
                if(!text(command,"type").equals("Tpa"))throw new IllegalStateException("Crew action did not complete; no inventory operation replayed");
                int retry=member.has("tpaRetries")?member.get("tpaRetries").getAsInt()+1:1;if(retry>2)throw new IllegalStateException("Crew TPA did not reach the stash after three attempts; carried kits retained");
                member.addProperty("tpaRetries",retry);return command(run,request,member,command);
            }
            if(text(command,"type").equals("StashResupply")){
                int picked=StashCatalog.integer(receipt,"picked",0,command.get("count").getAsInt()),cursor=StashCatalog.integer(receipt,"nextPick",member.get("cursor").getAsInt(),command.getAsJsonArray("picks").size());
                boolean exhausted=flag(receipt,"sourceExhausted");
                if(exhausted&&cursor!=command.getAsJsonArray("picks").size()||picked==0&&!exhausted)throw new IllegalArgumentException("Invalid crew pickup receipt");
                member.addProperty("batch",picked);member.addProperty("cursor",exhausted?0:cursor);member.addProperty("emptyPasses",picked==0?member.get("emptyPasses").getAsInt()+1:0);
                if(!text(receipt,"sourceIssue").isEmpty())team.addProperty("sourceIssue",text(receipt,"sourceIssue"));
            }
            if(text(command,"type").equals("StashDeposit")){
                int requested=command.get("count").getAsInt(),n=receipt.has("deposited")?StashCatalog.integer(receipt,"deposited",0,requested):requested;
                int missing=receipt.has("missing")?StashCatalog.integer(receipt,"missing",0,requested):0;
                if(n+missing!=requested)throw new IllegalArgumentException("Invalid crew deposit receipt");
                team.addProperty("missing",(team.has("missing")?team.get("missing").getAsInt():0)+missing);
                team.addProperty("delivered",team.get("delivered").getAsInt()+n);member.addProperty("delivered",member.get("delivered").getAsInt()+n);
                String kit=team.get("kit").getAsString();JsonObject totals=team.getAsJsonObject("byKit");totals.addProperty(kit,(totals.has(kit)?totals.get(kit).getAsInt():0)+n);
            }
            if(text(team,"phase").equals("pickup")&&text(command,"type").equals("Wait"))member.addProperty("batch",0);
            member.addProperty("ready",true);member.addProperty("readyStep",step);member.add("pendingRequest",request.deepCopy());
        }else if(step>0&&!request.equals(member.get("pendingRequest")))throw new IllegalArgumentException("Crew barrier receipt changed during retry");
        if(flag(team,"done")){JsonObject done=new JsonObject();done.addProperty("done",true);done.addProperty("delivered",team.get("delivered").getAsInt());done.addProperty("missing",team.has("missing")?team.get("missing").getAsInt():0);done.add("byKit",team.get("byKit").deepCopy());return reply(run,request,done);}
        long travelBudget=Math.max(team.getAsJsonObject("source").get("homeCooldownTicks").getAsLong()+team.getAsJsonObject("source").get("homeWarmupTicks").getAsLong(),team.getAsJsonObject("destination").get("homeCooldownTicks").getAsLong()+team.getAsJsonObject("destination").get("homeWarmupTicks").getAsLong());
        if(clock-team.get("phaseAt").getAsLong()>1_800_000L+(travelBudget+args.get("tpaWarmupTicks").getAsLong())*50L)throw new IllegalStateException("Crew batch timed out after its configured travel allowance; carried kits retained");
        if(members.entrySet().stream().allMatch(e->flag(e.getValue().getAsJsonObject(),"ready"))){
            String phase=text(team,"phase");
            if(phase.startsWith("travel-")){
                JsonObject site=team.getAsJsonObject(phase.equals("travel-source")?"source":"destination");
                if(ids.stream().anyMatch(id->!at(observations.get(UUID.fromString(id)),task,site,now)))return waiting(run,team,"arrival observations");
                advance(team,phase.equals("travel-source")?"pickup":"deposit",clock);
            }else if(phase.equals("pickup")){
                if(members.entrySet().stream().anyMatch(e->e.getValue().getAsJsonObject().get("batch").getAsInt()>0))advance(team,"travel-destination",clock);
                else if(members.entrySet().stream().allMatch(e->e.getValue().getAsJsonObject().get("emptyPasses").getAsInt()>=2)){
                    team.addProperty("index",team.get("index").getAsInt()+1);
                    if(team.get("index").getAsInt()>=args.getAsJsonArray("kitTypeIds").size()){team.addProperty("done",true);JsonObject done=new JsonObject();done.addProperty("done",true);done.addProperty("delivered",team.get("delivered").getAsInt());done.addProperty("missing",team.has("missing")?team.get("missing").getAsInt():0);done.add("byKit",team.get("byKit").deepCopy());return reply(run,request,done);}
                    planType(root,task,team,ids);advance(team,"travel-source",clock);
                }else advance(team,"travel-source",clock);
            }else{
                if(team.has("sourceIssue"))throw new IllegalStateException("Carried batch deposited; source needs attention: "+text(team,"sourceIssue"));
                advance(team,"travel-source",clock);
            }
        }
        if(flag(member,"ready"))return waiting(run,team,"other workers finishing their batch");
        String phase=text(team,"phase");JsonObject action;
        if(phase.startsWith("travel-")){
            JsonObject site=team.getAsJsonObject(phase.equals("travel-source")?"source":"destination");
            if(worker.equals(text(args,"leader"))){action=site.deepCopy();action.addProperty("type","StashVisit");}
            else if(at(observations.get(UUID.fromString(worker)),task,site,now)){action=new JsonObject();action.addProperty("type","Wait");action.addProperty("ticks",1);}
            else{
                JsonObject leader=task.getAsJsonObject("runs").getAsJsonObject(text(args,"leader"));
                boolean sent=leader.has("action")&&text(leader.getAsJsonObject("action"),"type").equals("StashVisit")&&text(leader.getAsJsonObject("action"),"name").equals(text(site,"name"))&&text(leader,"stashTravelToken").equals(text(leader,"token"))&&leader.has("stashTravel")&&flag(leader.getAsJsonObject("stashTravel"),"homeSent");
                if(!sent&&!at(observations.get(UUID.fromString(text(args,"leader"))),task,site,now))return waiting(run,team,"leader /home command");
                action=new JsonObject();action.addProperty("type","Tpa");action.addProperty("target",text(args,"leader"));action.addProperty("warmupTicks",args.get("tpaWarmupTicks").getAsInt());action.addProperty("timeoutTicks",args.get("tpaWarmupTicks").getAsInt()+1200);action.addProperty("acceptDelayTicks",10);action.addProperty("radius",8);action.addProperty("allowFailure",true);action.add("arrivalStash",site.deepCopy());
            }
        }else if(phase.equals("pickup")){
            // Crew barriers still lease one kit type; incidental side-deposits belong to the ordinary delivery workflow.
            member.addProperty("batch",0);action=member.getAsJsonObject("plan").deepCopy();action.remove("knownKitTypeIds");action.addProperty("pickIndex",member.get("cursor").getAsInt());action.addProperty("requireLocal",true);
            if(action.getAsJsonArray("picks").isEmpty()||member.get("emptyPasses").getAsInt()>=2){member.addProperty("emptyPasses",2);action=new JsonObject();action.addProperty("type","Wait");action.addProperty("ticks",1);}else action.addProperty("type","StashResupply");
        }else{
            int count=member.get("batch").getAsInt();
            if(count==0){action=new JsonObject();action.addProperty("type","Wait");action.addProperty("ticks",1);}else{action=member.getAsJsonObject("plan").getAsJsonObject("targetStash").deepCopy();action.addProperty("type","StashDeposit");action.addProperty("count",count);action.addProperty("requireLocal",true);}
        }
        return command(run,request,member,action);
    }
    private static void planType(Path root,JsonObject task,JsonObject team,List<String> ids){
        JsonObject args=task.getAsJsonObject("args").deepCopy();String leader=text(args,"leader"),kit=args.getAsJsonArray("kitTypeIds").get(team.get("index").getAsInt()).getAsString();args.remove("kitTypeIds");args.remove("leader");args.remove("tpaWarmupTicks");args.remove("depositCarried");args.addProperty("kitTypeId",kit);
        JsonObject plan=StashCatalog.kitAction(root,text(task,"crew"),text(task,"server")+"\n"+text(task,"dimension"),args,leader);
        var picks=plan.getAsJsonArray("picks").asList().stream().map(StashCatalog::sourcePosition).sorted(Comparator.comparingInt((JsonObject p)->p.get("x").getAsInt()).thenComparingInt(p->p.get("z").getAsInt()).thenComparingInt(p->p.get("y").getAsInt())).toList();
        team.addProperty("kit",kit);team.add("source",site(plan));team.add("destination",site(plan.getAsJsonObject("targetStash")));
        for(int i=0;i<ids.size();i++){
            JsonObject own=plan.deepCopy();JsonArray assigned=new JsonArray();for(int p=i;p<picks.size();p+=ids.size())assigned.add(picks.get(p).deepCopy());own.add("picks",assigned);
            JsonObject member=team.getAsJsonObject("members").getAsJsonObject(ids.get(i));member.add("plan",own);member.addProperty("cursor",0);member.addProperty("emptyPasses",0);member.addProperty("batch",0);
        }
    }
    private static JsonObject site(JsonObject plan){JsonObject site=new JsonObject();for(String key:List.of("name","homeName","homeWarmupTicks","homeCooldownTicks","minX","minY","minZ","maxX","maxY","maxZ"))site.add(key,plan.get(key).deepCopy());return site;}
    private static boolean at(PlayerObservation observation,JsonObject task,JsonObject site,long now){return observation!=null&&observation.inWorld(text(task,"server")+"\n"+text(task,"dimension"),now)&&StashCatalog.nearby(site,observation.position(),256);}
    private static void advance(JsonObject team,String phase,long clock){team.addProperty("phase",phase);team.addProperty("phaseAt",clock);for(var e:team.getAsJsonObject("members").entrySet()){e.getValue().getAsJsonObject().addProperty("ready",false);e.getValue().getAsJsonObject().remove("tpaRetries");}}
    private static JsonObject waiting(JsonObject run,JsonObject team,String reason){run.addProperty("detail","Crew stash transfer · "+text(team,"phase")+" · waiting for "+reason);return null;}
    private static JsonObject command(JsonObject run,JsonObject request,JsonObject member,JsonObject action){JsonObject value=new JsonObject();value.add("action",action.deepCopy());member.addProperty("step",request.get("step").getAsInt());member.add("request",request.deepCopy());member.add("reply",value.deepCopy());member.addProperty("ready",false);return reply(run,request,value);}
    private static JsonObject reply(JsonObject run,JsonObject request,JsonObject value){run.add("kitPlan",value.deepCopy());run.add("kitPlanRequest",request.deepCopy());run.addProperty("kitPlanToken",text(run,"token"));return value.deepCopy();}
}
