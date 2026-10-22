package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import dev.monocle.client.systems.bots.BotHistory;
import static dev.monocle.coordinator.TaskWire.text;
import static dev.monocle.coordinator.StashCatalog.integer;

/** One courier, one leased stash; all state lives in the existing durable task journal. */
public final class StashSortExecution {
    public static final String WORKFLOW="task-stash-sort";
    private StashSortExecution() { }
    private static JsonObject object(){return new JsonObject();}
    private static String key(JsonObject p){return p.get("x").getAsInt()+","+p.get("y").getAsInt()+","+p.get("z").getAsInt();}
    private static JsonObject position(JsonObject p){JsonObject r=object();for(String a:List.of("x","y","z"))r.add(a,p.get(a).deepCopy());return r;}
    private static JsonObject column(JsonObject p){JsonObject r=position(p);r.add("axis",p.get("axis").deepCopy());return r;}
    private static long total(JsonObject stock){return stock.asMap().values().stream().mapToLong(JsonElement::getAsLong).sum();}
    private static long amount(JsonObject stock,String kit){return stock.has(kit)?stock.get(kit).getAsLong():0;}
    private static void change(JsonObject stock,String kit,long delta){long n=amount(stock,kit)+delta;if(n<0)throw new IllegalStateException("Sort receipt exceeds recorded stock");if(n==0)stock.remove(kit);else stock.addProperty(kit,n);}
    private static JsonObject state(JsonObject task){return task.getAsJsonObject("stashSort");}
    private static JsonObject at(JsonObject s,int i){return s.getAsJsonArray("columns").get(i).getAsJsonObject();}
    public static boolean holds(JsonObject task){return task.has("stashSort")&&!Set.of("Complete","Released").contains(text(state(task),"status"));}
    public static boolean sameScope(JsonObject task,String crew,String scope){return holds(task)&&text(task,"crew").equals(crew)&&(text(task,"server")+"\n"+text(task,"dimension")).equals(scope);}
    public static boolean canRetryRead(JsonObject task){return holds(task)&&text(task,"status").equals("Failed")&&!TaskWire.flag(task,"cancelled")&&!state(task).has("cargo")&&state(task).has("pending")&&text(state(task).getAsJsonObject("pending"),"kind").equals("Scan");}
    public static void retryRead(Path root,JsonObject task){
        if(!canRetryRead(task)||!BotHistory.taskFinished(task,false))throw new IllegalStateException("Only a stopped verification scan with no cargo can be retried");
        validateSaved(task);JsonObject s=state(task);checkDefinition(root,task);
        String worker=text(s,"worker");JsonObject old=task.getAsJsonObject("runs").getAsJsonObject(worker),failure=object();failure.addProperty("run",text(old,"id"));failure.addProperty("detail",text(old,"detail"));failure.addProperty("at",System.currentTimeMillis());failure.add("column",s.getAsJsonObject("pending").get("column").deepCopy());s.add("lastReadFailure",failure);
        at(s,s.getAsJsonObject("pending").get("column").getAsInt()).addProperty("checked",false);
        for(String field:List.of("pending","reply","replyRequest","replyToken","waitingInventory","freeSlots"))s.remove(field);
        s.addProperty("sequence",0);s.addProperty("needsProbe",true);
        JsonObject run=object();run.addProperty("id",UUID.randomUUID().toString());run.addProperty("status","Queued");task.getAsJsonObject("runs").add(worker,run);task.addProperty("status","Queued");task.addProperty("paused",false);task.remove("finishedAt");
        conservation(s);
    }
    public static void validateSaved(JsonObject task){
        if(!task.has("stashSort"))return;JsonObject s=state(task);integer(s,"version",1,1);integer(s,"sequence",0,2_000_000);integer(s,"moved",0,Integer.MAX_VALUE);integer(s,"batches",0,2_000_000);
        if(!Set.of("Running","Complete","Released").contains(text(s,"status"))||!task.getAsJsonObject("runs").has(UUID.fromString(text(s,"worker")).toString())||!text(s,"name").equals(text(task.getAsJsonObject("args"),"name")))throw new IllegalArgumentException("Invalid saved sorting ownership");
        JsonArray columns=s.getAsJsonArray("columns");if(columns.isEmpty()||columns.size()>2048)throw new IllegalArgumentException("Invalid saved sorting columns");integer(s,"cursor",0,columns.size());StashCatalog.plan(s.getAsJsonObject("bounds"));
        for(JsonElement value:columns){JsonObject c=value.getAsJsonObject();StashCatalog.checkedColumn(s.getAsJsonObject("bounds"),c);integer(c,"capacity",1,13824);if(c.getAsJsonArray("cells").isEmpty()||c.getAsJsonArray("cells").size()>64)throw new IllegalArgumentException("Invalid saved sorting footprint");for(String kit:c.getAsJsonObject("stock").keySet())integer(c.getAsJsonObject("stock"),kit,1,Integer.MAX_VALUE);}
        if(s.has("cargo")){JsonObject c=s.getAsJsonObject("cargo");integer(c,"from",0,columns.size()-1);integer(c,"to",0,columns.size()-1);integer(c,"picked",0,36);integer(c,"deposited",0,c.get("picked").getAsInt());UUID.fromString(text(c,"batch"));}
        if(s.has("pending")&&s.getAsJsonObject("pending").has("scanRetries"))integer(s.getAsJsonObject("pending"),"scanRetries",0,3);
        conservation(s);
    }
    public static JsonObject checkedRequest(JsonObject a){
        if(!a.keySet().containsAll(Set.of("type","sequence"))||!Set.of("type","sequence","receipt").containsAll(a.keySet())||!text(a,"type").equals("StashSortPlan"))throw new IllegalArgumentException("Invalid sorting plan request");
        integer(a,"sequence",0,2_000_000);if(a.has("receipt")){if(!a.get("receipt").isJsonObject())throw new IllegalArgumentException("Sorting needs an object receipt");TaskFiles.jsonBytes(a.getAsJsonObject("receipt"),16_384);}return a.deepCopy();
    }
    public static JsonObject prepare(Path root,String crew,String scope,JsonObject args,String worker){
        JsonObject db=StashCatalog.get(root,crew,scope,text(args,"name")),cats=KitCategories.get(root,crew,scope);
        List<String> order=strings(args,"categoryOrder"),selected=args.has("categoryIds")?strings(args,"categoryIds"):null;
        int buffers=args.has("buffers")?integer(args,"buffers",1,16):4,slots=args.has("carrySlots")?integer(args,"carrySlots",1,36):34,growth=args.has("growthColumns")?integer(args,"growthColumns",0,2):1;
        JsonObject plan=StashSortPlanner.preview(db,cats,buffers,slots,order,selected,growth);
        if(!text(args,"planId").equals(text(plan,"planId")))throw new IllegalStateException("Sorting preview changed; refresh it before starting");
        if(!TaskWire.flag(plan,"feasibleForMovableColumns")||!TaskWire.flag(plan.getAsJsonObject("summary"),"canStageLargestColumn"))throw new IllegalStateException("Sort layout needs more working space");
        JsonObject s=object();s.addProperty("version",1);s.addProperty("status","Running");s.addProperty("name",text(args,"name"));s.addProperty("worker",UUID.fromString(worker).toString());s.addProperty("planId",text(plan,"planId"));s.addProperty("categoryRevision",text(cats,"revision"));s.addProperty("definitionRevision",StashCatalog.definitionRevision(db));s.addProperty("carrySlots",slots);s.addProperty("sequence",0);s.addProperty("cursor",0);s.addProperty("moved",0);s.addProperty("batches",0);s.addProperty("needsProbe",true);
        s.add("bounds",StashCatalog.route(root,crew,scope,db.getAsJsonObject("bounds"),worker));s.add("kitTypes",db.get("kitTypes").deepCopy());s.add("aliases",db.get("kitAliases")==null?object():db.get("kitAliases").deepCopy());s.add("categories",plan.get("categoryDefinitions").deepCopy());s.add("columns",new JsonArray());s.add("initialStock",object());s.add("kitGroups",object());
        JsonObject definitions=s.getAsJsonObject("categories"),groups=s.getAsJsonObject("kitGroups");
        for(var e:db.getAsJsonObject("kitTypes").entrySet()){
            String id=KitTypes.canonical(db,e.getKey());List<String> matches=definitions.entrySet().stream().filter(c->plan.getAsJsonArray("selectedCategoryIds").contains(new JsonPrimitive(c.getKey()))&&KitCategories.matches(c.getValue().getAsJsonObject(),id,e.getValue().getAsJsonObject().getAsJsonObject("items"))).map(Map.Entry::getKey).toList();
            if(matches.size()==1)groups.addProperty(id,matches.getFirst());
        }
        for(JsonElement value:plan.getAsJsonArray("columns")){
            JsonObject c=value.getAsJsonObject().deepCopy();if(text(c,"target").equals("preserve"))continue;
            c.add("stock",object());c.add("cells",new JsonArray());c.add("footprint",new JsonArray());Set<String> footprint=new LinkedHashSet<>();
            for(JsonElement k:c.getAsJsonArray("containers")){
                JsonObject record=db.getAsJsonObject("containers").getAsJsonObject(k.getAsString()),cell=position(record);cell.add("block",record.get("block").deepCopy());cell.add("capacity",record.has("slotCount")?record.get("slotCount").deepCopy():record.get("capacity").deepCopy());c.getAsJsonArray("cells").add(cell);
                if(record.has("parts"))for(JsonElement part:record.getAsJsonArray("parts")){JsonObject p=part.getAsJsonObject();if(footprint.add(key(p)))c.getAsJsonArray("footprint").add(position(p));}
                else if(footprint.add(key(record)))c.getAsJsonArray("footprint").add(position(record));
                for(JsonElement box:record.getAsJsonArray("shulkers")){JsonObject b=box.getAsJsonObject();String kit=KitTypes.canonical(db,StashCatalog.kitTypeId(b));if(!groups.has(kit))throw new IllegalStateException("A movable kit has no unique category");if(b.get("quantity").getAsLong()!=1)throw new IllegalArgumentException("Unstack shulker boxes before write sorting");change(c.getAsJsonObject("stock"),kit,1);change(s.getAsJsonObject("initialStock"),kit,1);}
            }
            if(c.getAsJsonArray("cells").isEmpty()||c.getAsJsonArray("cells").size()>64||footprint.size()>128)throw new IllegalArgumentException("Write sorting supports 1–64 mapped containers per column");
            c.addProperty("finished",false);c.addProperty("checked",false);s.getAsJsonArray("columns").add(c);
        }
        // Keep the original empty buffers available until every destination/growth column is settled.
        List<JsonElement> columns=new ArrayList<>(s.getAsJsonArray("columns").asList());columns.sort(Comparator.comparing(c->text(c.getAsJsonObject(),"target").equals("buffer")));JsonArray sorted=new JsonArray();columns.forEach(sorted::add);s.add("columns",sorted);
        if(groups.isEmpty()||s.getAsJsonObject("initialStock").size()>128)throw new IllegalArgumentException("Write sorting supports 1–128 known kit variants");
        conservation(s);return s;
    }
    private static List<String> strings(JsonObject o,String field){List<String> r=new ArrayList<>();if(o.has(field)){if(!o.get(field).isJsonArray())throw new IllegalArgumentException(field+" must be an array");for(JsonElement e:o.getAsJsonArray(field)){if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Expected category IDs");r.add(e.getAsString());}}return r;}
    private static JsonObject aliases(JsonObject s){JsonObject db=object();db.add("kitTypes",s.get("kitTypes"));db.add("kitAliases",s.get("aliases"));return db;}
    public static void validateAction(JsonObject task,JsonObject action){
        if(!task.has("stashSort")){if(action.has("sortBatch")||action.has("sortFootprint")||text(action,"type").equals("StashSortPlan"))throw new IllegalArgumentException("Sorting actions require a sorting job");return;}
        if(text(action,"type").equals("StashSortPlan")){checkedRequest(action);return;}
        JsonObject pending=state(task).getAsJsonObject("pending");
        if(pending==null||!matchesInstruction(task,pending,action))throw new IllegalArgumentException("Worker left its journaled sorting instruction");
    }
    private static boolean matchesInstruction(JsonObject task,JsonObject pending,JsonObject action){
        JsonObject expected=pending.getAsJsonObject("action");
        // The game runtime attaches the job dimension when it dispatches a stash scan.
        if(expected!=null&&text(expected,"type").equals("StashScan")&&!expected.has("dimension")&&action.has("dimension")){expected=expected.deepCopy();expected.add("dimension",task.get("dimension").deepCopy());}
        if(expected!=null&&text(expected,"type").equals("StashResupply")&&!expected.has("pickIndex")&&action.has("pickIndex")){expected=expected.deepCopy();expected.addProperty("pickIndex",0);}
        return expected!=null&&action.equals(expected);
    }
    public static JsonObject next(Path root,JsonObject task,JsonObject run,String worker){
        JsonObject s=state(task),request=checkedRequest(run.getAsJsonObject("action"));
        if(!text(s,"worker").equals(worker))throw new IllegalArgumentException("Sorting instruction belongs to another courier");
        if(text(s,"replyToken").equals(text(run,"token"))){if(!request.equals(s.get("replyRequest")))throw new IllegalArgumentException("Sorting request changed during retry");return s.getAsJsonObject("reply").deepCopy();}
        if(!text(s,"worker").equals(worker)||!holds(task))throw new IllegalStateException("This sorting lease is not active");
        if(integer(request,"sequence",0,2_000_000)!=s.get("sequence").getAsInt())throw new IllegalStateException("Sorting sequence changed; reconcile this execution");
        checkDefinition(root,task);JsonObject result=retryUnreadScan(s);
        if(result==null){if(s.has("pending"))finishInstruction(root,task,request.has("receipt")?request.getAsJsonObject("receipt"):object());result=choose(s);}
        s.addProperty("sequence",s.get("sequence").getAsInt()+1);result.add("sequence",s.get("sequence").deepCopy());
        s.add("reply",result.deepCopy());s.add("replyRequest",request.deepCopy());s.addProperty("replyToken",text(run,"token"));conservation(s);return result;
    }
    private static void checkDefinition(Path root,JsonObject task){
        JsonObject s=state(task),meta=StashCatalog.sortRows(root,text(task,"crew"),text(task,"server")+"\n"+text(task,"dimension"),text(s,"name"),Set.of());
        if(!StashCatalog.definitionRevision(meta).equals(text(s,"definitionRevision"))||!text(KitCategories.get(root,text(task,"crew"),text(task,"server")+"\n"+text(task,"dimension")),"revision").equals(text(s,"categoryRevision")))throw new IllegalStateException("Sort definition/category rules changed; stop and inspect the saved cargo");
    }
    private static JsonObject retryUnreadScan(JsonObject s){
        JsonObject p=s.getAsJsonObject("pending");if(p==null||!text(p,"kind").equals("Scan"))return null;
        JsonObject c=at(s,p.get("column").getAsInt()),e=p.getAsJsonObject("evidence");boolean unread=false;
        for(JsonElement cell:c.getAsJsonArray("cells")){JsonObject o=e.getAsJsonObject(key(cell.getAsJsonObject()));if(o==null||TaskWire.flag(o,"retryable"))unread=true;else if(TaskWire.flag(o,"invalid"))return null;}
        if(!unread)return null;int attempts=p.has("scanRetries")?integer(p,"scanRetries",0,3):0;
        if(attempts==3)throw new IllegalStateException("Sort verification is still unread after three retries; inspect the container and Resume to retry safely");
        JsonObject result=scan(s,p.get("column").getAsInt(),TaskWire.flag(p,"finish"));s.getAsJsonObject("pending").addProperty("scanRetries",attempts+1);return result;
    }
    private static JsonObject instruction(JsonObject s,String kind,JsonObject action){JsonObject p=object();p.addProperty("kind",kind);p.add("action",action);p.add("evidence",object());s.add("pending",p);JsonObject r=object();r.add("action",action.deepCopy());return r;}
    private static JsonObject scan(JsonObject s,int i,boolean finish){
        JsonObject c=at(s,i),a=s.getAsJsonObject("bounds").deepCopy();a.addProperty("type","StashScan");a.addProperty("lazyMode",false);a.addProperty("scanMode","Full");a.add("scanCells",c.get("cells").deepCopy());a.add("sortFootprint",c.get("footprint").deepCopy());
        JsonObject r=instruction(s,"Scan",a);s.getAsJsonObject("pending").addProperty("column",i);s.getAsJsonObject("pending").addProperty("finish",finish);return r;
    }
    private static String group(JsonObject s,String kit){return text(s.getAsJsonObject("kitGroups"),kit);}
    private static long desired(JsonObject c){return TaskWire.flag(c,"growth")?0:c.get("plannedKits").getAsLong();}
    private static long categoryStock(JsonObject s,JsonObject c){long n=0;for(var e:c.getAsJsonObject("stock").entrySet())if(group(s,e.getKey()).equals(text(c,"target")))n+=e.getValue().getAsLong();return n;}
    private static long free(JsonObject c){return Math.max(0,c.get("capacity").getAsLong()-total(c.getAsJsonObject("stock")));}
    private static JsonObject choose(JsonObject s){
        if(s.has("cargo")){JsonObject cargo=s.getAsJsonObject("cargo");if(cargo.get("picked").getAsInt()>cargo.get("deposited").getAsInt())return deposit(s);s.remove("cargo");}
        if(TaskWire.flag(s,"waitingInventory")){JsonObject a=object();a.addProperty("type","Wait");a.addProperty("ticks",40);return instruction(s,"Wait",a);}
        int cursor=s.get("cursor").getAsInt(),size=s.getAsJsonArray("columns").size();
        while(cursor<size&&TaskWire.flag(at(s,cursor),"finished"))cursor++;
        s.addProperty("cursor",cursor);
        if(cursor==size){s.addProperty("status","Complete");JsonObject r=object();r.addProperty("done",true);r.add("moved",s.get("moved").deepCopy());r.add("batches",s.get("batches").deepCopy());return r;}
        JsonObject c=at(s,cursor);if(!TaskWire.flag(c,"checked"))return scan(s,cursor,false);
        String outgoing="";long excess=categoryStock(s,c)-desired(c);
        for(var e:c.getAsJsonObject("stock").entrySet())if(!group(s,e.getKey()).equals(text(c,"target"))||desired(c)==0||excess>0){outgoing=e.getKey();break;}
        int from=cursor,to=-1;String kit=outgoing;long count=0;
        if(!outgoing.isEmpty()){
            long best=Long.MAX_VALUE;
            for(int i=0;i<size;i++){JsonObject d=at(s,i);if(i==cursor||TaskWire.flag(d,"finished")||free(d)==0)continue;
                long rank=(text(d,"target").equals(group(s,kit))&&!TaskWire.flag(d,"growth")?0:text(d,"target").equals("buffer")?1:2)*1_000_000L+Math.abs(i-cursor);
                if(rank<best){best=rank;to=i;}
            }
            if(to<0)throw new IllegalStateException("Sort has no verified working space; cargo retained");
            count=Math.min(amount(c.getAsJsonObject("stock"),kit),free(at(s,to)));if(group(s,kit).equals(text(c,"target"))&&desired(c)>0)count=Math.min(count,excess);
        }else if(categoryStock(s,c)<desired(c)){
            long best=Long.MAX_VALUE;to=cursor;from=-1;
            for(int i=0;i<size;i++)if(i!=cursor&&!TaskWire.flag(at(s,i),"finished"))for(var e:at(s,i).getAsJsonObject("stock").entrySet())if(group(s,e.getKey()).equals(text(c,"target"))){long rank=(text(at(s,i),"target").equals("buffer")?0:1)*1_000_000L+Math.abs(i-cursor);if(rank<best){best=rank;from=i;kit=e.getKey();}}
            if(from<0)throw new IllegalStateException("Recorded category stock is missing; verify source columns before replanning");
            count=Math.min(amount(at(s,from).getAsJsonObject("stock"),kit),desired(c)-categoryStock(s,c));
        }else return scan(s,cursor,true);
        if(!TaskWire.flag(at(s,from),"checked"))return scan(s,from,false);
        if(!TaskWire.flag(at(s,to),"checked"))return scan(s,to,false);
        if(TaskWire.flag(s,"needsProbe")){JsonObject p=object();p.addProperty("kind","Probe");s.add("pending",p);JsonObject r=object();r.addProperty("probe",true);return r;}
        int n=(int)Math.min(count,Math.min(s.get("carrySlots").getAsInt(),s.get("freeSlots").getAsInt()));if(n<1)throw new IllegalStateException("Free an inventory slot before resuming this sort");
        String batch=UUID.randomUUID().toString();JsonObject a=kitAction(s,from,kit,n);a.addProperty("type","StashResupply");a.addProperty("depositMode","Carry");a.addProperty("transferAll",true);a.addProperty("sortBatch",batch);a.addProperty("pickIndex",0);
        JsonArray picks=new JsonArray();for(JsonElement value:at(s,from).getAsJsonArray("cells")){JsonObject p=position(value.getAsJsonObject());p.addProperty("slot",0);p.addProperty("dynamic",true);p.addProperty("kitTypeId",kit);p.addProperty("incomplete",false);p.addProperty("resource",text(s.getAsJsonObject("kitTypes").getAsJsonObject(kit),"item"));picks.add(p);}a.add("picks",picks);
        JsonObject r=instruction(s,"Take",a),pending=s.getAsJsonObject("pending");pending.addProperty("from",from);pending.addProperty("to",to);pending.addProperty("kit",kit);pending.addProperty("batch",batch);pending.addProperty("count",n);return r;
    }
    private static JsonObject kitAction(JsonObject s,int index,String kit,int count){
        JsonObject a=s.getAsJsonObject("bounds").deepCopy(),type=s.getAsJsonObject("kitTypes").getAsJsonObject(kit),ids=object();for(var e:KitTypes.identities(s.getAsJsonObject("kitTypes")).entrySet())if(text(type,"familyId").equals(text(e.getValue().getAsJsonObject(),"familyId")))ids.add(e.getKey(),e.getValue());
        a.addProperty("kitTypeId",kit);a.addProperty("count",count);a.addProperty("includeIncomplete",true);a.addProperty("incomplete",false);a.add("kitExemplar",type.get("items").deepCopy());a.add("kitIdentities",ids);a.add("sortFootprint",at(s,index).get("footprint").deepCopy());return a;
    }
    private static JsonObject deposit(JsonObject s){
        JsonObject cargo=s.getAsJsonObject("cargo");int to=cargo.get("to").getAsInt();JsonObject a=kitAction(s,to,text(cargo,"kit"),cargo.get("picked").getAsInt());a.addProperty("type","StashDeposit");a.addProperty("sortBatch",text(cargo,"batch"));a.add("targetColumn",column(at(s,to)));
        return instruction(s,"Store",a);
    }
    private static void finishInstruction(Path root,JsonObject task,JsonObject receipt){
        JsonObject s=state(task),p=s.getAsJsonObject("pending");String kind=text(p,"kind");
        if(kind.equals("Probe")){int free=integer(receipt,"freeSlots",0,36),boxes=integer(receipt,"shulkers",0,2304);s.add("freeSlots",receipt.get("freeSlots").deepCopy());s.addProperty("waitingInventory",free==0||boxes>0);s.addProperty("needsProbe",free==0||boxes>0);}
        else if(kind.equals("Wait"))s.addProperty("waitingInventory",false);
        else if(kind.equals("Scan")){
            JsonObject c=at(s,p.get("column").getAsInt()),stock=object(),evidence=p.getAsJsonObject("evidence");
            for(JsonElement cell:c.getAsJsonArray("cells")){JsonObject observation=evidence.getAsJsonObject(key(cell.getAsJsonObject()));if(observation==null||TaskWire.flag(observation,"invalid"))throw new IllegalStateException("Sort verification has unread/changed containers; no replacement authorized");for(var e:observation.getAsJsonObject("stock").entrySet())change(stock,e.getKey(),e.getValue().getAsLong());}
            if(!stock.equals(c.get("stock")))throw new IllegalStateException("Column "+c.get("index")+" at "+key(c)+" differs from the sorting ledger; inspect/rescan rather than replay uncertain moves");
            if(TaskWire.flag(p,"finish")){
                JsonArray cols=new JsonArray();cols.add(column(c));String category=s.getAsJsonObject("categories").has(text(c,"target"))?text(c,"target"):"";
                String crew=text(task,"crew"),scope=text(task,"server")+"\n"+text(task,"dimension"),name=text(s,"name");JsonObject db=StashCatalog.get(root,crew,scope,name);
                StashCatalog.setColumnReservations(root,crew,scope,name,text(StashCatalog.summary(db),"revision"),"",category,cols);c.addProperty("finished",true);
            }c.addProperty("checked",true);
        }else if(kind.equals("Take")){
            JsonObject cargo=s.getAsJsonObject("cargo");int picked=integer(receipt,"picked",1,p.get("count").getAsInt());if(cargo==null||cargo.get("picked").getAsInt()!=picked)throw new IllegalStateException("Pickup result lacks matching server-confirmed evidence; cargo retained");
        }else if(kind.equals("Store")){
            JsonObject cargo=s.getAsJsonObject("cargo");int deposited=integer(receipt,"deposited",0,cargo.get("picked").getAsInt());
            if((receipt.has("missing")&&integer(receipt,"missing",0,36)!=0)||deposited!=cargo.get("picked").getAsInt()||deposited!=cargo.get("deposited").getAsInt())throw new IllegalStateException("Sorting deposit is incomplete; retained cargo must be reconciled before continuing");
            s.addProperty("moved",s.get("moved").getAsLong()+deposited);s.addProperty("batches",s.get("batches").getAsInt()+1);s.remove("cargo");s.addProperty("needsProbe",true);
        }else throw new IllegalStateException("Unknown sorting instruction");s.remove("pending");
    }
    public static void finding(JsonObject task,JsonObject run,JsonObject finding){
        if(!task.has("stashSort")||!Objects.equals(run.get("action"),finding.get("action"))||!text(run,"token").equals(text(finding,"token")))return;
        JsonObject s=state(task),p=s.getAsJsonObject("pending");if(p==null||!text(p,"kind").equals("Scan"))return;JsonObject o=StashCatalog.observation(p.getAsJsonObject("action"),finding.getAsJsonObject("observation")),c=at(s,p.get("column").getAsInt());
        JsonObject cell=c.getAsJsonArray("cells").asList().stream().map(JsonElement::getAsJsonObject).filter(v->key(v).equals(key(o))).findFirst().orElseThrow(()->new IllegalArgumentException("Scan left its sorting column"));
        JsonObject compact=object(),stock=object();boolean invalid=!text(o,"status").equals("observed")||TaskWire.flag(o,"inferred")||!o.has("slotCount")||!o.get("slotCount").equals(cell.get("capacity"))||!text(o,"block").equals(text(cell,"block"));Set<Integer> slots=new HashSet<>();
        for(JsonElement box:o.getAsJsonArray("shulkers")){JsonObject b=box.getAsJsonObject();String kit=KitTypes.canonical(aliases(s),KitTypes.sortIdentity(b,s.getAsJsonObject("kitTypes")));if(!s.getAsJsonObject("kitGroups").has(kit)||!TaskWire.flag(b,"contentsKnown")||!b.has("slot")||!slots.add(b.get("slot").getAsInt())||b.get("quantity").getAsLong()!=1)invalid=true;else change(stock,kit,1);}
        invalid|=!o.has("occupiedSlots")||o.get("occupiedSlots").getAsInt()!=slots.size();compact.addProperty("invalid",invalid);compact.addProperty("retryable",text(o,"status").equals("unscanned"));compact.add("stock",stock);p.getAsJsonObject("evidence").add(key(cell),compact);
    }
    public static void evidence(JsonObject task,JsonObject run,JsonObject message){
        if(!task.has("stashSort")||!message.has("action"))return;JsonObject s=state(task),p=s.getAsJsonObject("pending");if(p==null||!matchesInstruction(task,p,message.getAsJsonObject("action")))return;
        String kind=text(p,"kind");if(kind.equals("Take")&&message.has("stashWithdrawal")){
            JsonArray boxes=message.getAsJsonObject("stashWithdrawal").getAsJsonArray("withdrawn");int count=boxes.size();if(count>p.get("count").getAsInt())throw new IllegalArgumentException("Sorting withdrawal exceeds batch");
            JsonObject cargo=s.has("cargo")?s.getAsJsonObject("cargo"):object();int old=cargo.has("picked")?cargo.get("picked").getAsInt():0;if(count<old)return;
            Set<String> cells=new HashSet<>();at(s,p.get("from").getAsInt()).getAsJsonArray("cells").forEach(c->cells.add(key(c.getAsJsonObject())));
            for(JsonElement box:boxes){JsonObject b=box.getAsJsonObject();if(!cells.contains(key(b))||!text(p,"kit").equals(KitTypes.canonical(aliases(s),text(b,"kitTypeId"))))throw new IllegalArgumentException("Sorting pickup left its manifest");}
            change(at(s,p.get("from").getAsInt()).getAsJsonObject("stock"),text(p,"kit"),old-count);
            for(String f:List.of("from","to","kit","batch"))cargo.add(f,p.get(f).deepCopy());cargo.addProperty("picked",count);cargo.addProperty("deposited",0);s.add("cargo",cargo);
        }else if(kind.equals("Store")&&message.has("stashDeposit")){
            JsonObject cargo=s.getAsJsonObject("cargo"),r=message.getAsJsonObject("stashDeposit");if(!TaskWire.flag(r,"receiptComplete"))return;int n=integer(r,"deposited",0,cargo.get("picked").getAsInt()),old=cargo.get("deposited").getAsInt();if(n<old)return;
            JsonArray boxes=r.getAsJsonArray("depositedBoxes");if(boxes.size()!=n)throw new IllegalArgumentException("Sorting deposit lacks exact kit evidence");for(JsonElement box:boxes)if(!text(cargo,"kit").equals(KitTypes.canonical(aliases(s),StashCatalog.kitTypeId(box.getAsJsonObject()))))throw new IllegalArgumentException("Sorting deposit contains another kit");
            change(at(s,cargo.get("to").getAsInt()).getAsJsonObject("stock"),text(cargo,"kit"),n-old);cargo.addProperty("deposited",n);
        }conservation(s);
    }
    public static JsonObject depositFinding(Path root,JsonObject task,JsonObject run,JsonObject message){
        JsonObject s=state(task),p=s.getAsJsonObject("pending");validateAction(task,message.getAsJsonObject("action"));if(!text(p,"kind").equals("Store"))throw new IllegalArgumentException("No journaled sorting deposit");
        JsonObject a=p.getAsJsonObject("action"),cargo=s.getAsJsonObject("cargo"),c=at(s,cargo.get("to").getAsInt());int delivery=integer(message,"delivery",1,4096);
        String token=text(message,"token"),hash=TaskFiles.hash(message.toString());if(token.equals(text(p,"findingToken"))&&p.has("delivery")&&delivery<=p.get("delivery").getAsInt()){if(delivery<p.get("delivery").getAsInt())return null;if(!hash.equals(text(p,"findingHash")))throw new IllegalArgumentException("Sort finding changed during retry");return p.getAsJsonObject("ack").deepCopy();}
        int last=token.equals(text(p,"findingToken"))&&p.has("delivery")?p.get("delivery").getAsInt():0;if(delivery!=last+1)throw new IllegalArgumentException("Sorting deposit observation has a gap");
        Set<String> known=new LinkedHashSet<>();known.add(text(cargo,"kit"));boolean permit=false;String error="";
        if(TaskWire.flag(message,"exhaustedColumn"))error="Sorting destination filled unexpectedly; cargo retained";
        if(message.has("observation")){
            JsonObject o=StashCatalog.observation(a,message.getAsJsonObject("observation"));Set<String> cells=new HashSet<>();c.getAsJsonArray("cells").forEach(v->cells.add(key(v.getAsJsonObject())));
            if(!cells.contains(key(o)))throw new IllegalArgumentException("Sorting deposit observation left its destination");
            StashCatalog.save(root,text(task,"crew"),text(task,"server")+"\n"+text(task,"dimension"),s.getAsJsonObject("bounds"),o);
            boolean recognized=true;for(JsonElement box:o.getAsJsonArray("shulkers")){JsonObject b=box.getAsJsonObject();String kit=KitTypes.canonical(aliases(s),KitTypes.sortIdentity(b,s.getAsJsonObject("kitTypes")));recognized&=s.getAsJsonObject("initialStock").has(kit)&&TaskWire.flag(b,"contentsKnown")&&b.get("quantity").getAsInt()==1;String raw=StashCatalog.kitTypeId(b);if(!raw.isEmpty())known.add(raw);}
            JsonObject cell=c.getAsJsonArray("cells").asList().stream().map(JsonElement::getAsJsonObject).filter(v->key(v).equals(key(o))).findFirst().orElseThrow();
            permit=recognized&&o.has("slotCount")&&o.get("slotCount").equals(cell.get("capacity"))&&StashCatalog.columnContentsMatch(o,known);if(!permit)error="Unexpected or loose destination contents; sorting cargo retained";
        }
        JsonObject rule=KitCategories.definition("sort-buffer","Sorting working column","Kits",known);
        JsonObject ack=TaskWire.message("stash-ack");for(String f:List.of("run","token","delivery"))ack.add(f,message.get(f).deepCopy());ack.addProperty("deposit",true);ack.addProperty("permit",permit);ack.add("column",column(c));ack.add("columnCategory",rule);if(!error.isEmpty())ack.addProperty("error",error);
        p.addProperty("findingToken",token);p.addProperty("findingHash",hash);p.addProperty("delivery",delivery);p.add("ack",ack.deepCopy());run.addProperty("depositToken",token);return ack;
    }
    private static void conservation(JsonObject s){
        JsonObject actual=object();for(JsonElement c:s.getAsJsonArray("columns"))for(var e:c.getAsJsonObject().getAsJsonObject("stock").entrySet())change(actual,e.getKey(),e.getValue().getAsLong());
        if(s.has("cargo")){JsonObject c=s.getAsJsonObject("cargo");change(actual,text(c,"kit"),c.get("picked").getAsLong()-c.get("deposited").getAsLong());}
        if(!actual.equals(s.get("initialStock")))throw new IllegalStateException("Sorting ledger lost conservation; no new movement authorized");
    }
    public static JsonObject summary(JsonObject task){JsonObject s=state(task),r=object();for(String f:List.of("status","name","planId","moved","batches","cursor","worker"))r.add(f,s.get(f).deepCopy());r.addProperty("leased",holds(task));r.addProperty("retryableRead",canRetryRead(task));r.addProperty("columns",s.getAsJsonArray("columns").size());if(s.has("cargo"))r.add("cargo",s.get("cargo").deepCopy());if(s.has("pending")){r.addProperty("phase",text(s.getAsJsonObject("pending"),"kind"));if(s.getAsJsonObject("pending").has("scanRetries"))r.add("scanRetries",s.getAsJsonObject("pending").get("scanRetries").deepCopy());}return r;}
}
