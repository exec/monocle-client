package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Cold restart checks: a warm cache must not hide an incomplete durable catalog. */
final class StashCatalogShouldCheck {
    private static final String SCOPE="server\nminecraft:the_end",WORKER="00000000-0000-0000-0000-000000000001";
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("Unsafe catalog operation accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private static Path file(Path root,String name){return root.resolve("stashes").resolve(TaskFiles.hash("A\n"+SCOPE+"\n"+name)+".sqlite");}
    private static JsonObject plan(String name,int min,int max){JsonObject p=json("{homeName:'main',minY:100,maxY:100,minZ:0,maxZ:0}");p.addProperty("name",name);p.addProperty("minX",min);p.addProperty("maxX",max);return StashCatalog.plan(p);}
    private static JsonObject box(String item,int count){JsonObject b=json("{slot:0,item:'minecraft:blue_shulker_box',name:'empty',quantity:1,contentsKnown:true,items:{}}");b.getAsJsonObject("items").addProperty(item,count);return b;}
    private static JsonObject observation(int x,JsonObject... boxes){JsonObject o=json("{y:100,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}");o.addProperty("x",x);JsonObject items=o.getAsJsonObject("items");for(JsonObject b:boxes){o.getAsJsonArray("shulkers").add(b.deepCopy());String item=TaskWire.text(b,"item");items.addProperty(item,(items.has(item)?items.get(item).getAsInt():0)+b.get("quantity").getAsInt());for(var e:b.getAsJsonObject("items").entrySet())items.addProperty(e.getKey(),(items.has(e.getKey())?items.get(e.getKey()).getAsLong():0)+e.getValue().getAsLong()*b.get("quantity").getAsInt());}return o;}
    private static JsonObject cold(Path file){StashStore.clearCache();return StashStore.read(file);}

    static void run()throws Exception{
        scanFindingRetries();summaryCache();scannedFullColumns();kitReserves();assumedEmptyStorage();
        kitVariants();legacyAliases();crossStashVariants();journal();largeDelta();sqliteCrashRecovery();sqliteScanRate();exportRetry();withdrawalPrefixes();depositEvidence();transferStock();freshReadAndEstimates();
        System.out.println("Stash checks passed: kit variants, SQLite cold reopen, targeted updates, rollback, atomic scan checkpoints and durable export receipts.");
    }
    private static void assumedEmptyStorage()throws Exception{
        Path root=Files.createTempDirectory("monocle-assumed-empty-");JsonObject p=plan("Empty",0,2),box=box("minecraft:obsidian",1728);String kit=KitTypes.id(box);
        StashCatalog.save(root,"A",SCOPE,p,observation(0,box));
        JsonObject unknown=observation(1);unknown.addProperty("status","unscanned");unknown.addProperty("capacity",54);unknown.addProperty("reason","Storage discovered; contents not yet observed");StashCatalog.save(root,"A",SCOPE,p,unknown);
        JsonObject full=observation(0,box);full.addProperty("slotCount",1);full.addProperty("occupiedSlots",1);StashCatalog.save(root,"A",SCOPE,p,full);
        assert StashCatalog.reserveColumn(root,"A",SCOPE,"Empty",kit,Set.of())==null : "Default excludes unopened chests";
        JsonObject before=StashCatalog.list(root).get(0).getAsJsonObject(),changes=json("{assumeEmpty:true}");
        JsonObject saved=StashCatalog.revise(root,"A",SCOPE,"Empty",TaskWire.text(before,"revision"),changes);
        assert saved.getAsJsonObject("kitCounts").equals(before.getAsJsonObject("kitCounts")) : "Assumption never rewrites known contents";
        assert saved.get("assumedEmptyContainers").getAsInt()==1&&saved.get("assumedEmptySlots").getAsInt()==54;
        assert saved.get("observed").equals(before.get("observed")) : "Assumed capacity is not an observation";
        assert StashCatalog.reserveColumn(root,"A",SCOPE,"Empty",kit,Set.of()).get("x").getAsInt()==1;
        assert cold(file(root,"Empty")).getAsJsonObject("bounds").get("assumeEmpty").getAsBoolean();
        JsonObject action=p.deepCopy();action.addProperty("type","StashDeposit");action.addProperty("kitTypeId",kit);action.add("kitExemplar",box.get("items").deepCopy());
        JsonObject task=json("{id:'task',crew:'A',server:'server',dimension:'minecraft:the_end',runs:{}}"),run=json("{id:'run',token:'token'}");run.add("action",action);task.getAsJsonObject("runs").add(WORKER,run);
        JsonObject message=TaskWire.message("stash-observation");message.addProperty("task","task");message.addProperty("run","run");message.addProperty("token","token");message.add("action",action);message.addProperty("delivery",1);
        JsonObject ack=StashCatalog.accept(root,task,run,WORKER,"A",message);
        assert ack.has("column")&&!ack.get("permit").getAsBoolean() : "Assumption grants a location, never permission to move items";
        message.addProperty("delivery",2);message.add("observation",observation(1));
        assert StashCatalog.accept(root,task,run,WORKER,"A",message).get("permit").getAsBoolean() : "A real empty observation is still required";
        unknown.addProperty("reason","Contents changed; rescan required");StashCatalog.save(root,"A",SCOPE,p,unknown);
        assert StashCatalog.reserveColumn(root,"A",SCOPE,"Empty",kit,Set.of())==null : "Invalidated observations are not newly discovered empty storage";
        JsonObject other=observation(1,box("minecraft:crying_obsidian",1728));StashCatalog.save(root,"A",SCOPE,p,other);
        assert StashCatalog.reserveColumn(root,"A",SCOPE,"Empty",kit,Set.of())==null : "Real incompatible contents override the assumption";
        unknown.addProperty("x",2);unknown.addProperty("reason","Storage discovered; contents not yet observed");StashCatalog.save(root,"A",SCOPE,p,unknown);
        assert StashCatalog.reserveColumn(root,"A",SCOPE,"Empty",kit,Set.of()).get("x").getAsInt()==2;
        saved=StashCatalog.list(root).get(0).getAsJsonObject();
        StashCatalog.revise(root,"A",SCOPE,"Empty",TaskWire.text(saved,"revision"),json("{assumeEmpty:false}"));
        assert StashCatalog.reserveColumn(root,"A",SCOPE,"Empty",kit,Set.of())==null : "Disabling the assumption excludes even reserved unopened candidates";
        assert StashCatalog.kitPicks(StashCatalog.get(root,"A",SCOPE,"Empty"),kit,36,false).get(0).getAsJsonObject().get("x").getAsInt()==0 : "Source selection still requires observed contents";
        JsonObject invalid=p.deepCopy();invalid.addProperty("assumeEmpty","yes");rejects(()->StashCatalog.plan(invalid));
    }
    private static void kitReserves()throws Exception{
        Path root=Files.createTempDirectory("monocle-kit-reserve-");JsonObject p=plan("Reserves",0,1),box=box("minecraft:obsidian",1728);String kit=KitTypes.id(box);
        StashCatalog.save(root,"A",SCOPE,p,observation(0,box));
        JsonObject before=StashCatalog.list(root).get(0).getAsJsonObject();String revision=TaskWire.text(before,"revision");
        JsonObject saved=StashCatalog.setKitReserve(root,"A",SCOPE,"Reserves",revision,kit,500);
        assert saved.getAsJsonObject("kitReserves").get(kit).getAsInt()==500;
        rejects(()->StashCatalog.setKitReserve(root,"A",SCOPE,"Reserves",revision,kit,600));
        String current=TaskWire.text(saved,"revision");
        rejects(()->StashCatalog.setKitReserve(root,"A",SCOPE,"Reserves",current,kit,-1));
        rejects(()->StashCatalog.setKitReserve(root,"A",SCOPE,"Reserves",current,kit,1_000_001));
        rejects(()->StashCatalog.setKitReserve(root,"A",SCOPE,"Reserves",current,UUID.randomUUID().toString(),500));
        JsonObject reopened=cold(file(root,"Reserves"));
        assert reopened.getAsJsonObject("kitReserves").get(kit).getAsInt()==500 : "Floors survive SQLite cold reopen";
        assert StashCatalog.summary(reopened).getAsJsonObject("kitCounts").equals(before.getAsJsonObject("kitCounts")) : "Setting reserves never changes stock";
        StashCatalog.save(root,"A",SCOPE,p,observation(1,box));
        saved=StashCatalog.list(root).get(0).getAsJsonObject();
        assert saved.getAsJsonObject("kitReserves").get(kit).getAsInt()==500 : "New observations preserve floors";
        saved=StashCatalog.setKitReserve(root,"A",SCOPE,"Reserves",TaskWire.text(saved,"revision"),kit,0);
        assert saved.getAsJsonObject("kitReserves").isEmpty();
    }
    private static void scannedFullColumns()throws Exception{
        Path root=Files.createTempDirectory("monocle-scan-full-columns-");String name="Full scan";
        JsonObject p=plan(name,0,2);p.addProperty("maxY",102);p.addProperty("maxZ",2);
        JsonObject box=box("minecraft:obsidian",1728);String kit=KitTypes.id(box);
        for(int y=100;y<=102;y++){
            JsonObject o=observation(0,box);o.addProperty("y",y);o.addProperty("z",y-100);o.addProperty("slotCount",1);o.addProperty("occupiedSlots",1);StashCatalog.save(root,"A",SCOPE,p,o);
        }
        JsonObject empty=observation(2);empty.addProperty("slotCount",1);empty.addProperty("occupiedSlots",0);StashCatalog.save(root,"A",SCOPE,p,empty);
        assert StashCatalog.reserveColumn(root,"A",SCOPE,name,kit,Set.of()).get("x").getAsInt()==2 : "Skip a completely full scanned diagonal column";
        JsonObject free=observation(0);free.addProperty("y",102);free.addProperty("z",2);free.addProperty("slotCount",1);free.addProperty("occupiedSlots",0);StashCatalog.save(root,"A",SCOPE,p,free);
        assert StashCatalog.reserveColumn(root,"A",SCOPE,name,kit,Set.of()).get("x").getAsInt()==0 : "A new observation of space restores the matching column";
        JsonObject full=observation(0,box);full.addProperty("y",102);full.addProperty("z",2);full.addProperty("slotCount",1);full.addProperty("occupiedSlots",1);StashCatalog.save(root,"A",SCOPE,p,full);
        JsonObject db=StashCatalog.get(root,"A",SCOPE,name);db.add("fullColumns",json("{'0,0':{x:0,y:100,z:0,axis:'X'}}"));StashStore.write(file(root,name),db);
        JsonArray withdrawn=new JsonArray();withdrawn.add(json("{x:0,y:102,z:2}"));StashCatalog.invalidateWithdrawn(root,"A",SCOPE,name,withdrawn);
        assert StashCatalog.get(root,"A",SCOPE,name).getAsJsonObject("fullColumns").isEmpty() : "Confirmed withdrawals clear durable full-column markers";
        assert StashCatalog.reserveColumn(root,"A",SCOPE,name,kit,Set.of()).get("x").getAsInt()==0 : "A confirmed withdrawal makes a full scanned column eligible again";
        withdrawn=new JsonArray();withdrawn.add(json("{x:0,y:100,z:0}"));StashCatalog.invalidateWithdrawn(root,"A",SCOPE,name,withdrawn);
        assert StashCatalog.reserveColumn(root,"A",SCOPE,name,kit,Set.of()).get("x").getAsInt()==0 : "Confirmed bottom withdrawals may be revalidated before depositing";
    }
    private static void summaryCache()throws Exception{
        Path root=Files.createTempDirectory("monocle-summary-cache-");JsonObject p=plan("Cache",0,1);
        StashCatalog.save(root,"A",SCOPE,p,observation(0,box("minecraft:obsidian",1728)));
        JsonArray first=StashCatalog.list(root);String revision=TaskWire.text(first.get(0).getAsJsonObject(),"revision");
        first.get(0).getAsJsonObject().getAsJsonObject("items").addProperty("minecraft:obsidian",0);
        assert StashCatalog.list(root).get(0).getAsJsonObject().getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==1728 : "Callers cannot mutate cached summaries";
        String before=StashStore.revision(file(root,"Cache"));
        StashCatalog.save(root,"A",SCOPE,p,observation(1,box("minecraft:obsidian",1728)));
        assert !before.equals(StashStore.revision(file(root,"Cache")));
        assert !revision.equals(TaskWire.text(StashCatalog.list(root).get(0).getAsJsonObject(),"revision"));
        before=StashStore.revision(file(root,"Cache"));
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file(root,"Cache"));var s=c.createStatement()){s.executeUpdate("UPDATE metadata SET value='123' WHERE key='updatedAt'");}
        assert !before.equals(StashStore.revision(file(root,"Cache"))) : "External SQL commits invalidate summaries";
        assert StashCatalog.list(root).get(0).getAsJsonObject().get("updatedAt").getAsInt()==123;
        StashStore.clearCache();assert StashCatalog.list(root).get(0).getAsJsonObject().get("updatedAt").getAsInt()==123;
        StashStore.clearCache();Files.delete(file(root,"Cache"));assert StashCatalog.list(root).isEmpty();
        JsonObject canonical=json("{a:[null,true,1,'<>&'],b:{c:'x',d:2}}");
        assert StashCatalog.revisionHash(canonical).equals(TaskFiles.hash(canonical.toString())) : "Streaming revisions preserve existing canonical hashes";
        assert StashCatalog.revisionHash(canonical).equals(StashCatalog.revisionHash(json("{b:{d:2,c:'x'},a:[null,true,1,'<>&']}")));
    }
    private static void scanFindingRetries(){
        assert !StashCatalog.scanAckBatchReady(0,1_000_000_000L)&&!StashCatalog.scanAckBatchReady(1,99_999_999L)
            &&StashCatalog.scanAckBatchReady(1,100_000_000L)&&StashCatalog.scanAckBatchReady(8,0)
            : "Scan receipts coalesce briefly, with immediate full-window commits and a bounded quiet-window delay";
        JsonObject run=json("{stashToken:'a',stashDelivery:2}"),message=json("{token:'a',delivery:2,action:{type:'StashScan'}}");
        assert StashCatalog.scanFindingRecorded(run,message);
        message.addProperty("delivery",1);assert StashCatalog.scanFindingRecorded(run,message);
        message.addProperty("delivery",3);assert !StashCatalog.scanFindingRecorded(run,message);
        message.addProperty("delivery",1);message.addProperty("token","b");assert !StashCatalog.scanFindingRecorded(run,message);
        message.addProperty("token","a");message.getAsJsonObject("action").addProperty("type","StashDeposit");
        assert !StashCatalog.scanFindingRecorded(run,message) : "Mutating deposit receipts keep their checkpoint path";
        assert !StashCatalog.scanFindingRecorded(new JsonObject(),message);
    }
    private static void kitVariants()throws Exception{
        Path root=Files.createTempDirectory("monocle-kit-variants-");JsonObject p=plan("Source",0,4),a=box("minecraft:obsidian",1728),b=box("minecraft:experience_bottle",1728);
        StashCatalog.save(root,"A",SCOPE,p,observation(0,a));StashCatalog.save(root,"A",SCOPE,p,observation(1,b));
        JsonObject partial=a.deepCopy();partial.getAsJsonObject("items").addProperty("minecraft:obsidian",64);StashCatalog.save(root,"A",SCOPE,p,observation(2,partial));
        JsonObject empty=a.deepCopy();empty.add("items",new JsonObject());StashCatalog.save(root,"A",SCOPE,p,observation(3,empty));
        JsonObject forged=b.deepCopy();forged.addProperty("kitTypeId",KitTypes.id(a));StashCatalog.save(root,"A",SCOPE,p,observation(4,forged));
        JsonObject db=cold(file(root,"Source")),summary=StashCatalog.summary(db),counts=summary.getAsJsonObject("kitCounts");
        assert db.getAsJsonObject("kitTypes").size()==3 : "Disjoint contents and truly empty boxes are separate variants, not incomplete copies";
        assert counts.getAsJsonObject(KitTypes.id(a)).get("complete").getAsInt()==1&&counts.getAsJsonObject(KitTypes.id(a)).get("incomplete").getAsInt()==1;
        assert counts.getAsJsonObject(KitTypes.id(b)).get("complete").getAsInt()==2&&counts.getAsJsonObject(KitTypes.id(b)).get("incomplete").getAsInt()==0 : "Worker-supplied IDs cannot relabel unrelated contents";
        JsonObject criteria=json("{includeIncomplete:true}");criteria.addProperty("kitTypeId",KitTypes.id(a));criteria.add("kitExemplar",a.get("items").deepCopy());criteria.add("kitIdentities",KitTypes.identities(db.getAsJsonObject("kitTypes")));
        assert KitTypes.matches(partial,criteria)&&!KitTypes.matches(b,criteria)&&!KitTypes.matches(empty,criteria);
        criteria.remove("kitIdentities");criteria.addProperty("kitTypeId",KitTypes.family(a));assert KitTypes.matches(partial,criteria)&&!KitTypes.matches(b,criteria) : "Captured v1 jobs still discriminate by their exemplar";
        JsonObject target=plan("Target",10,10);StashCatalog.save(root,"A",SCOPE,target,observation(10));
        JsonObject request=p.deepCopy();request.addProperty("kitTypeId",KitTypes.id(a));request.addProperty("count",1);request.addProperty("destination","Stash");request.addProperty("targetStashName","Target");
        JsonObject action=StashCatalog.kitAction(root,"A",SCOPE,request,WORKER);
        assert action.getAsJsonObject("kitIdentities").has(KitTypes.id(b))&&!action.getAsJsonArray("knownKitTypeIds").asList().contains(new JsonPrimitive(KitTypes.family(a))) : "Ambiguous families cannot recognize unrelated incidental boxes by name alone";
    }
    private static void withdrawalPrefixes()throws Exception{
        Path root=Files.createTempDirectory("monocle-withdrawal-prefix-");JsonObject p=plan("Source",0,2),box=box("minecraft:obsidian",1728);
        for(int x=0;x<3;x++)StashCatalog.record(root,"A",SCOPE,p,observation(x,box));
        String execution=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();JsonObject run=json("{}");run.addProperty("id",execution);
        JsonObject message=json("{}");message.addProperty("run",execution);message.addProperty("token",token);JsonObject action=p.deepCopy();action.addProperty("type","StashResupply");message.add("action",action);
        JsonObject receipt=json("{stash:'Source',withdrawn:[]}");message.add("stashWithdrawal",receipt);
        JsonArray picks=receipt.getAsJsonArray("withdrawn");picks.add(json("{x:0,y:100,z:0,items:{'minecraft:obsidian':1728}}"));
        StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,message);
        assert TaskWire.text(StashCatalog.get(root,"A",SCOPE,"Source").getAsJsonObject("containers").getAsJsonObject("0,100,0"),"status").equals("unscanned");
        StashCatalog.record(root,"A",SCOPE,p,observation(0,box));picks.add(json("{x:1,y:100,z:0,items:{'minecraft:obsidian':1728}}"));
        StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,message);JsonObject db=cold(file(root,"Source"));
        assert TaskWire.text(db.getAsJsonObject("containers").getAsJsonObject("0,100,0"),"status").equals("observed")&&TaskWire.text(db.getAsJsonObject("containers").getAsJsonObject("1,100,0"),"status").equals("unscanned") : "The growing receipt touches only newly withdrawn sources";
        StashCatalog.record(root,"A",SCOPE,p,observation(1,box));JsonObject lostCheckpoint=json("{}");lostCheckpoint.addProperty("id",execution);
        StashCatalog.withdrawalReceipt(root,"A",SCOPE,lostCheckpoint,message);assert lostCheckpoint.get("stashWithdrawalCount").getAsInt()==2;
        assert TaskWire.text(cold(file(root,"Source")).getAsJsonObject("containers").getAsJsonObject("1,100,0"),"status").equals("observed") : "The catalog receipt survives loss of the host task checkpoint and cannot erase a later observation";
        JsonObject mutated=message.deepCopy();mutated.getAsJsonObject("stashWithdrawal").getAsJsonArray("withdrawn").get(0).getAsJsonObject().addProperty("x",2);JsonObject before=StashCatalog.get(root,"A",SCOPE,"Source");rejects(()->StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,mutated));assert StashCatalog.get(root,"A",SCOPE,"Source").equals(before);
        JsonObject shorter=message.deepCopy();shorter.getAsJsonObject("stashWithdrawal").getAsJsonArray("withdrawn").remove(1);StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,shorter);assert run.get("stashWithdrawalCount").getAsInt()==2;
        message.addProperty("token",UUID.randomUUID().toString());StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,message);assert TaskWire.text(cold(file(root,"Source")).getAsJsonObject("containers").getAsJsonObject("0,100,0"),"status").equals("unscanned");
        JsonObject wrong=message.deepCopy();wrong.getAsJsonObject("action").addProperty("type","Wait");rejects(()->StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,wrong));
    }
    private static void depositEvidence()throws Exception{
        Path root=Files.createTempDirectory("monocle-deposit-evidence-");JsonObject p=plan("Target",0,0);StashCatalog.record(root,"A",SCOPE,p,observation(0));
        JsonObject action=p.deepCopy();action.addProperty("type","StashDeposit");JsonObject message=json("{token:'deposit',stashDeposit:{stash:'Target',touched:[{x:0,y:100,z:0}],deposited:1,receiptComplete:true,depositedBoxes:[]}}");message.add("action",action);
        JsonObject actual=box("minecraft:obsidian",64);actual.addProperty("x",0);actual.addProperty("y",100);actual.addProperty("z",0);message.getAsJsonObject("stashDeposit").getAsJsonArray("depositedBoxes").add(actual);
        JsonObject run=json("{depositToken:'deposit'}");StashCatalog.depositReceipt(root,"A",SCOPE,run,message);assert run.getAsJsonObject("stashDepositEvidence").getAsJsonArray("boxes").get(0).getAsJsonObject().getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==64 : "Actual partial-kit quantities are retained, never replaced with an exemplar";
        JsonObject next=message.deepCopy();next.getAsJsonObject("stashDeposit").addProperty("deposited",2);next.getAsJsonObject("stashDeposit").getAsJsonArray("depositedBoxes").add(actual.deepCopy());StashCatalog.depositReceipt(root,"A",SCOPE,run,next);assert run.getAsJsonObject("stashDepositEvidence").get("confirmed").getAsInt()==2;
        JsonObject changed=next.deepCopy();changed.getAsJsonObject("stashDeposit").getAsJsonArray("depositedBoxes").get(0).getAsJsonObject().getAsJsonObject("items").addProperty("minecraft:obsidian",1728);rejects(()->StashCatalog.depositReceipt(root,"A",SCOPE,run,changed));
        StashCatalog.depositReceipt(root,"A",SCOPE,run,message);assert run.getAsJsonObject("stashDepositEvidence").get("confirmed").getAsInt()==2 : "An older report cannot lower cumulative evidence";
    }
    private static void transferStock()throws Exception{
        Path root=Files.createTempDirectory("monocle-transfer-stock-");JsonObject p=plan("Target",0,1),full=box("minecraft:experience_bottle",1728);
        StashCatalog.record(root,"A",SCOPE,p,observation(0,full));StashCatalog.record(root,"A",SCOPE,p,observation(1,full));
        String id=KitTypes.id(full),execution=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        JsonObject action=p.deepCopy();action.addProperty("type","StashDeposit");action.addProperty("kitTypeId",id);
        JsonObject message=json("{stashDeposit:{stash:'Target',touched:[{x:0,y:100,z:0}],deposited:1,receiptComplete:true,depositedBoxes:[]}}");message.add("action",action);message.addProperty("token",token);
        JsonObject run=json("{}");run.addProperty("id",execution);run.addProperty("depositToken",token);
        StashCatalog.beginTransfer(root,"A",SCOPE,run,message);
        JsonObject actual=full.deepCopy();actual.addProperty("x",0);actual.addProperty("y",100);actual.addProperty("z",0);message.getAsJsonObject("stashDeposit").getAsJsonArray("depositedBoxes").add(actual);
        StashCatalog.depositReceipt(root,"A",SCOPE,run,message);
        StashCatalog.record(root,"A",SCOPE,p,observation(0));
        JsonObject db=cold(file(root,"Target"));
        assert StashStock.estimates(db).getAsJsonObject(id).get("complete").getAsInt()==3 : "A draining top chest cannot erase deposited kits";
        assert StashCatalog.summary(db).getAsJsonObject("transferItems").get("minecraft:experience_bottle").getAsLong()==5184 : "Resource estimates conserve actual deposited contents";
        assert StashCatalog.summary(db).getAsJsonObject("kitCounts").getAsJsonObject(id).get("complete").getAsInt()==1 : "Observed and estimated counts remain separate";
        JsonObject lost=json("{}");lost.addProperty("id",execution);lost.addProperty("depositToken",token);StashCatalog.depositReceipt(root,"A",SCOPE,lost,message);
        assert StashStock.estimates(cold(file(root,"Target"))).getAsJsonObject(id).get("complete").getAsInt()==3 : "Deposit replay after lost task checkpoint cannot add twice";
        JsonObject changed=message.deepCopy();changed.getAsJsonObject("stashDeposit").getAsJsonArray("depositedBoxes").get(0).getAsJsonObject().getAsJsonObject("items").addProperty("minecraft:experience_bottle",64);rejects(()->StashCatalog.depositReceipt(root,"A",SCOPE,lost,changed));
        JsonObject withdrawal=json("{stashWithdrawal:{stash:'Target',withdrawn:[]}}");withdrawal.addProperty("run",execution);withdrawal.addProperty("token",UUID.randomUUID().toString());JsonObject source=p.deepCopy();source.addProperty("type","StashResupply");withdrawal.add("action",source);
        JsonObject pick=json("{x:1,y:100,z:0,items:{'minecraft:experience_bottle':1728}}");pick.addProperty("kitTypeId",id);withdrawal.getAsJsonObject("stashWithdrawal").getAsJsonArray("withdrawn").add(pick);
        StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,withdrawal);StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,withdrawal);
        assert StashStock.estimates(cold(file(root,"Target"))).getAsJsonObject(id).get("complete").getAsInt()==2 : "Withdrawal subtracts one verified box, not the whole invalidated chest";
        assert StashCatalog.summary(cold(file(root,"Target"))).getAsJsonObject("transferItems").get("minecraft:experience_bottle").getAsLong()==3456;
        JsonObject scan=p.deepCopy();scan.addProperty("type","StashScan");scan.addProperty("lazyMode",false);
        StashCatalog.record(root,"A",SCOPE,scan,observation(0));
        assert StashStock.estimates(cold(file(root,"Target"))).getAsJsonObject(id).get("complete").getAsInt()==2 : "Partial rescan cannot replace the balance";
        StashCatalog.record(root,"A",SCOPE,scan,observation(1,full));db=cold(file(root,"Target"));
        assert StashStock.estimates(db).getAsJsonObject(id).get("complete").getAsInt()==2 : "Discovery must finish before rebasing a scan";
        JsonObject completed=json("{stashScan:{phase:'Complete',unscanned:0,missingChunks:0}}");completed.add("action",scan);StashCatalog.beginTransfer(root,"A",SCOPE,run,completed);db=cold(file(root,"Target"));
        assert StashStock.estimates(db).getAsJsonObject(id).get("complete").getAsInt()==1 : "A complete fresh rescan reconciles external changes";
        assert !TaskWire.flag(db.getAsJsonObject("transferStock"),"uncertain");
        StashCatalog.depositReceipt(root,"A",SCOPE,lost,message);StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,withdrawal);
        assert StashStock.estimates(cold(file(root,"Target"))).getAsJsonObject(id).get("complete").getAsInt()==1 : "Reconciliation retains receipt deduplication";
        JsonObject partial=message.deepCopy();partial.addProperty("token",UUID.randomUUID().toString());partial.getAsJsonObject("stashDeposit").getAsJsonArray("depositedBoxes").get(0).getAsJsonObject().getAsJsonObject("items").addProperty("minecraft:experience_bottle",64);
        StashCatalog.depositReceipt(root,"A",SCOPE,lost,partial);db=cold(file(root,"Target"));
        assert StashStock.estimates(db).getAsJsonObject(id).get("incomplete").getAsInt()==1 : "Partial kits do not inflate complete stock";
        assert StashCatalog.summary(db).getAsJsonObject("transferItems").get("minecraft:experience_bottle").getAsLong()==1792 : "Partial deposit uses 64 actual bottles, never the 1728-bottle exemplar";
        assert !StashCatalog.wireSummary(StashCatalog.summary(db)).has("transferKitCounts") : "Detailed balances cannot bloat worker catalog frames";
        assert KitCategories.stock(db).getAsJsonObject(id).get("estimatedComplete").getAsInt()==1;
        JsonObject partialWithdrawal=withdrawal.deepCopy();partialWithdrawal.addProperty("token",UUID.randomUUID().toString());JsonObject removed=partialWithdrawal.getAsJsonObject("stashWithdrawal").getAsJsonArray("withdrawn").get(0).getAsJsonObject();removed.getAsJsonObject("items").addProperty("minecraft:experience_bottle",64);removed.addProperty("quantity",99);
        StashCatalog.withdrawalReceipt(root,"A",SCOPE,run,partialWithdrawal);db=cold(file(root,"Target"));
        assert StashStock.estimates(db).getAsJsonObject(id).get("complete").getAsInt()==1&&StashStock.estimates(db).getAsJsonObject(id).get("incomplete").getAsInt()==0 : "Each verified withdrawal is one actual box, even if source metadata contains a stack quantity";
        assert StashCatalog.summary(db).getAsJsonObject("transferItems").get("minecraft:experience_bottle").getAsLong()==1728;
    }

    private static void freshReadAndEstimates()throws Exception{
        Path root=Files.createTempDirectory("monocle-fresh-reads-");JsonObject p=plan("Source",0,1);StashCatalog.record(root,"A",SCOPE,p,observation(0));
        JsonObject db=StashCatalog.get(root,"A",SCOPE,"Source");db.getAsJsonObject("containers").getAsJsonObject("0,100,0").addProperty("observedAt",1);StashStore.write(file(root,"Source"),db);
        JsonObject message=json("{name:'Source'}");message.addProperty("eventId",UUID.randomUUID().toString());message.addProperty("basis",StashCatalog.observationBasis(db.getAsJsonObject("containers").getAsJsonObject("0,100,0")));StashCatalog.attachObservation(message,observation(0));
        assert TaskWire.text(StashCatalog.acceptLive(root,"A",SCOPE,WORKER,message),"outcome").equals("unchanged");
        db=StashCatalog.get(root,"A",SCOPE,"Source");long fresh=db.getAsJsonObject("containers").getAsJsonObject("0,100,0").get("observedAt").getAsLong();assert fresh>1;
        StashCatalog.acceptLive(root,"A",SCOPE,WORKER,message);assert cold(file(root,"Source")).getAsJsonObject("containers").getAsJsonObject("0,100,0").get("observedAt").getAsLong()==fresh : "A new read refreshes freshness; retrying its same event does not";
        JsonObject real=observation(0,box("minecraft:obsidian",1728)),estimate=observation(1,box("minecraft:obsidian",1728));estimate.addProperty("inferred",true);StashCatalog.record(root,"A",SCOPE,p,real);StashCatalog.record(root,"A",SCOPE,p,estimate);
        JsonObject summary=StashCatalog.summary(cold(file(root,"Source")));assert summary.getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==1728&&summary.getAsJsonObject("estimatedItems").get("minecraft:obsidian").getAsInt()==1728 : "Estimated and observed item quantities stay separate, just like kit counts";
        assert !StashCatalog.wireSummary(summary).has("estimatedItems") : "Detailed estimates must not inflate worker catalog frames";
    }
    private static void legacyAliases()throws Exception{
        Path root=Files.createTempDirectory("monocle-kit-legacy-");JsonObject p=plan("Legacy",0,1),a=box("minecraft:obsidian",1728),b=box("minecraft:experience_bottle",1728);
        JsonObject db=json("{version:1,name:'Legacy',crew:'A',containers:{},kitTypes:{},columnReservations:{}}");db.addProperty("scope",SCOPE);db.add("bounds",p);
        JsonObject type=a.deepCopy();type.remove("contentsKnown");type.remove("slot");type.addProperty("id",KitTypes.family(a));db.getAsJsonObject("kitTypes").add(KitTypes.family(a),type);
        db.getAsJsonObject("containers").add("0,100,0",observation(0,a));db.getAsJsonObject("containers").add("1,100,0",observation(1,b));
        JsonObject reservation=json("{manual:true,column:{x:0,y:100,z:0,axis:'Vertical'}}");reservation.addProperty("kitTypeId",KitTypes.family(a));db.getAsJsonObject("columnReservations").add("0,0",reservation);
        StashStore.write(file(root,"Legacy"),db);JsonObject original=StashStore.read(file(root,"Legacy"));
        JsonObject migrated=StashCatalog.get(root,"A",SCOPE,"Legacy");
        assert KitTypes.canonical(migrated,KitTypes.family(a)).equals(KitTypes.id(a));
        assert migrated.getAsJsonObject("columnReservations").getAsJsonObject("0,0").get("kitTypeId").getAsString().equals(KitTypes.id(a));
        assert migrated.getAsJsonObject("kitTypes").size()==2;
        assert StashCatalog.summary(migrated).get("revision").equals(StashCatalog.summary(StashCatalog.get(root,"A",SCOPE,"Legacy")).get("revision")) : "Read-only migrations have stable revisions";
        assert StashStore.read(file(root,"Legacy")).equals(original) : "A read must not rewrite user data";
        JsonObject request=p.deepCopy();request.addProperty("kitTypeId",KitTypes.family(a));request.addProperty("count",1);request.addProperty("destination","Ender Chest");
        assert StashCatalog.kitAction(root,"A",SCOPE,request,WORKER).get("kitTypeId").getAsString().equals(KitTypes.id(a));
        JsonObject target=plan("Target",10,10);StashCatalog.save(root,"A",SCOPE,target,observation(10));
        JsonArray columns=new JsonArray();columns.add(json("{x:10,y:100,z:0,axis:'Vertical'}"));
        JsonObject reserved=StashCatalog.setColumnReservations(root,"A",SCOPE,"Target",TaskWire.text(StashCatalog.summary(StashCatalog.get(root,"A",SCOPE,"Target")),"revision"),KitTypes.family(a),columns);
        assert reserved.getAsJsonObject("columns").getAsJsonObject("10,0").getAsJsonObject("reservation").get("kitTypeId").getAsString().equals(KitTypes.id(a));
        StashCatalog.record(root,"A",SCOPE,p,observation(0,a));
        assert cold(file(root,"Legacy")).getAsJsonObject("kitAliases").get(KitTypes.family(a)).getAsString().equals(KitTypes.id(a));
    }
    private static void crossStashVariants()throws Exception{
        Path root=Files.createTempDirectory("monocle-cross-stash-kit-");JsonObject source=plan("Source",0,1),target=plan("Target",10,11),partial=box("minecraft:obsidian",64),full=partial.deepCopy();full.getAsJsonObject("items").addProperty("minecraft:diamond_pickaxe",1);
        StashCatalog.save(root,"A",SCOPE,source,observation(0,partial));StashCatalog.save(root,"A",SCOPE,source,observation(1,full));
        String id=KitTypes.id(partial),destinationId=KitTypes.id(full);assert !id.equals(destinationId);
        StashCatalog.save(root,"A",SCOPE,target,observation(10,full));StashCatalog.save(root,"A",SCOPE,target,observation(11));
        JsonObject request=source.deepCopy();request.addProperty("kitTypeId",id);request.addProperty("count",1);request.addProperty("destination","Stash");request.addProperty("targetStashName","Target");request.add("targetColumn",json("{x:10,y:100,z:0,axis:'Vertical'}"));
        JsonObject action=StashCatalog.kitAction(root,"A",SCOPE,request,WORKER).getAsJsonObject("targetStash");action.addProperty("type","StashDeposit");
        assert KitTypes.matches(partial,action)&&KitTypes.matches(full,action) : "A related destination type must not override the requested full-kit family";
        JsonObject task=json("{id:'task',crew:'A',server:'server',dimension:'minecraft:the_end',runs:{}}"),run=json("{id:'run',token:'token'}");run.add("action",action);task.getAsJsonObject("runs").add(WORKER,run);
        JsonObject message=TaskWire.message("stash-observation");message.addProperty("task","task");message.addProperty("run","run");message.addProperty("token","token");message.add("action",action);message.addProperty("delivery",1);
        assert StashCatalog.accept(root,task,run,WORKER,"A",message).has("column") : "Existing related columns remain eligible across independently learned IDs";
        message.addProperty("delivery",2);message.add("observation",observation(10,full));assert StashCatalog.accept(root,task,run,WORKER,"A",message).get("permit").getAsBoolean();
        assert KitTypes.canonical(cold(file(root,"Target")),id).equals(destinationId);
        JsonObject blank=plan("Blank",20,20);StashCatalog.save(root,"A",SCOPE,blank,observation(20));request.addProperty("targetStashName","Blank");request.remove("targetColumn");
        action=StashCatalog.kitAction(root,"A",SCOPE,request,WORKER).getAsJsonObject("targetStash");action.addProperty("type","StashDeposit");run=json("{id:'run',token:'new-token'}");run.add("action",action);task.getAsJsonObject("runs").add(WORKER,run);message.add("action",action);message.addProperty("token","new-token");message.addProperty("delivery",1);message.remove("observation");
        assert StashCatalog.accept(root,task,run,WORKER,"A",message).has("column");message.addProperty("delivery",2);message.add("observation",observation(20));assert StashCatalog.accept(root,task,run,WORKER,"A",message).get("permit").getAsBoolean();
        message.addProperty("delivery",3);message.add("observation",observation(20,full));assert StashCatalog.accept(root,task,run,WORKER,"A",message).get("permit").getAsBoolean() : "A kit first indexed incomplete retains its ID when a full copy reaches a new stash";
        assert cold(file(root,"Blank")).getAsJsonObject("kitTypes").has(id);
    }
    private static void journal()throws Exception{
        Path root=Files.createTempDirectory("monocle-stash-sqlite-"),file=root.resolve("stash.sqlite");
        JsonObject data=json("{name:'Journal',containers:{'0':{value:1}}}");
        StashStore.write(file,data);JsonObject update=StashStore.read(file);
        update.getAsJsonObject("containers").getAsJsonObject("0").addProperty("value",2);update.addProperty("receipt","ack");
        StashStore.record(file,update,Set.of("0"));assert cold(file).equals(update) : "Container and receipt commit together";
        JsonObject detached=StashStore.read(file);detached.remove("receipt");assert StashStore.read(file).has("receipt");
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var s=c.createStatement()){
            try(var r=s.executeQuery("PRAGMA journal_mode")){assert r.next()&&r.getString(1).equals("wal");}
            s.execute("CREATE TRIGGER fail_row BEFORE INSERT ON containers WHEN NEW.key='bad' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        }
        JsonObject failed=update.deepCopy();failed.addProperty("receipt","must roll back");failed.getAsJsonObject("containers").add("bad",json("{value:3}"));
        rejects(()->StashStore.record(file,failed,Set.of("bad")));assert cold(file).equals(update) : "Failed rows cannot commit metadata or receipts";
        rejects(()->StashStore.update(file,Set.of("0"),(db,count)->{db.addProperty("receipt","bad");throw new IllegalStateException("failed edit");},null));
        assert cold(file).equals(update);
        JsonObject checkpoint=json("{binding:'scan-a',plan:'plan',scan:{cursor:1}}");
        StashStore.update(file,Set.of("0"),(db,count)->{assert count==1;db.addProperty("receipt","scan committed");return Set.of();},checkpoint);
        assert cold(file).get("receipt").getAsString().equals("scan committed")&&StashStore.checkpoint(file,"scan-a").equals(checkpoint);
        assert StashStore.checkpoint(file,"scan-b").isEmpty();
        JsonObject broken=checkpoint.deepCopy();broken.getAsJsonObject("scan").addProperty("cursor",2);
        rejects(()->StashStore.update(file,Set.of(),(db,count)->{db.getAsJsonObject("containers").add("bad",json("{value:4}"));return Set.of("bad");},broken));
        assert StashStore.checkpoint(file,"scan-a").equals(checkpoint) : "Failed observation cannot advance recovery";
        StashStore.clearCache();Files.delete(file);assert cold(file).isEmpty();StashStore.write(file,data);assert cold(file).equals(data);
        Path legacy=root.resolve("stashes/old.json");TaskFiles.write(legacy,data);
        assert StashCatalog.list(root).isEmpty() : "No implicit import of pre-SQLite catalogs";
        Path newer=root.resolve("newer.sqlite");
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+newer);var s=c.createStatement()){s.execute("PRAGMA user_version=99");}
        rejects(()->StashStore.read(newer));
    }
    private static void largeDelta()throws Exception{
        Path root=Files.createTempDirectory("monocle-large-sqlite-"),file=root.resolve("large.sqlite");JsonObject db=json("{containers:{},name:'Large'}");
        for(int i=0;i<16000;i++)db.getAsJsonObject("containers").add(Integer.toString(i),json("{items:{'minecraft:obsidian':1728},shulkers:[],status:'observed'}"));
        StashStore.write(file,db);long started=System.nanoTime();
        JsonObject changed=StashStore.update(file,Set.of("0"),(partial,count)->{
            assert count==16000&&partial.getAsJsonObject("containers").size()==1 : "One chest update must not load 16,000 unchanged rows";
            partial.getAsJsonObject("containers").remove("0");partial.getAsJsonObject("containers").add("16000",json("{status:'removed'}"));return Set.of("0","16000");
        },null);
        long elapsed=System.nanoTime()-started;
        assert changed.getAsJsonObject("containers").size()==1;
        JsonObject saved=cold(file);assert saved.getAsJsonObject("containers").size()==16000&&!saved.getAsJsonObject("containers").has("0")&&saved.getAsJsonObject("containers").has("16000");
        System.out.println("16,000-container SQLite targeted update: "+elapsed/1_000_000d+" ms (one row loaded).");
    }
    public static void main(String[] args)throws Exception{
        Path file=Path.of(args[0]);
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file)){
            c.setAutoCommit(false);
            try(var s=c.createStatement()){
                s.executeUpdate("UPDATE metadata SET value='2' WHERE key='revision'");
                s.executeUpdate("UPDATE containers SET value='{\"value\":2}' WHERE key='0'");
                s.executeUpdate("INSERT INTO scan_checkpoints(binding,plan,value) VALUES ('crash','plan','{\"cursor\":2}')");
            }
            if(Boolean.parseBoolean(args[1]))c.commit();
            Runtime.getRuntime().halt(17);
        }
    }
    private static void sqliteCrashRecovery()throws Exception{
        Path file=Files.createTempDirectory("monocle-sqlite-crash-").resolve("stash.sqlite");
        JsonObject initial=json("{revision:1,containers:{'0':{value:1}}}");StashStore.write(file,initial);StashStore.clearCache();
        for(boolean commit:List.of(false,true)){
            Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),StashCatalogShouldCheck.class.getName(),file.toString(),Boolean.toString(commit)).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if(!child.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Crash recovery child timed out");}
            assert child.exitValue()==17;
            JsonObject saved=cold(file);int expected=commit?2:1;
            assert saved.get("revision").getAsInt()==expected&&saved.getAsJsonObject("containers").getAsJsonObject("0").get("value").getAsInt()==expected;
            assert StashStore.checkpoint(file,"crash").isEmpty()!=commit : "Recovery and observations must survive a process crash as one commit";
        }
        // WAL permits a long-lived reader snapshot while another connection commits a chest update.
        try(var reader=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file)){
            reader.setAutoCommit(false);
            try(var s=reader.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key='revision'")){assert r.next()&&r.getInt(1)==2;}
            StashStore.update(file,Set.of("0"),(db,count)->{db.addProperty("revision",3);db.getAsJsonObject("containers").getAsJsonObject("0").addProperty("value",3);return Set.of("0");},null);
            try(var s=reader.createStatement();var r=s.executeQuery("SELECT value FROM containers WHERE key='0'")){assert r.next()&&json(r.getString(1)).get("value").getAsInt()==2 : "Reader must retain a consistent pre-write snapshot";}
            reader.commit();
        }
        assert cold(file).get("revision").getAsInt()==3;
    }
    private static void sqliteScanRate()throws Exception{
        Path root=Files.createTempDirectory("monocle-sqlite-scan-rate-");JsonObject p=plan("Bench",0,15999),o=observation(0,box("minecraft:obsidian",1728));
        StashCatalog.define(root,"Local",SCOPE,p);
        Path file=root.resolve("stashes").resolve(TaskFiles.hash("Local\n"+SCOPE+"\nBench")+".sqlite");
        JsonObject db=StashStore.read(file);
        for(int i=0;i<16000;i++){JsonObject row=o.deepCopy();row.addProperty("x",i);db.getAsJsonObject("containers").add(StashCatalog.containerKey(row),row);}
        StashStore.write(file,db);
        JsonObject checkpoint=json("{version:1,binding:'bench',plan:'bench',scan:{cursor:1,outbox:[]}}");
        for(int i=0;i<32;i++)checkpoint.getAsJsonObject("scan").getAsJsonArray("outbox").add(o.deepCopy());
        long started=System.nanoTime();
        for(int i=0;i<200;i++){o.addProperty("x",i);checkpoint.getAsJsonObject("scan").addProperty("cursor",i+1);StashCatalog.recordScan(root,SCOPE,p,o,checkpoint);}
        double milliseconds=(System.nanoTime()-started)/1_000_000d;
        assert StashStore.checkpoint(file,"bench").getAsJsonObject("scan").get("cursor").getAsInt()==200;
        assert cold(file).getAsJsonObject("containers").size()==16000;
        System.out.println("16,000-chest catalog, 200 real scan + recovery commits: "+milliseconds+" ms total, "+milliseconds/200+" ms/read (local synthetic benchmark, not game throughput).");
    }
    private static void exportRetry()throws Exception{
        Path root=Files.createTempDirectory("monocle-export-delta-"),file=file(root,"Export");JsonObject p=plan("Export",0,1);StashCatalog.define(root,"A",SCOPE,p);StashCatalog.record(root,"A",SCOPE,p,observation(0));StashCatalog.record(root,"A",SCOPE,p,observation(1));
        JsonObject message=TaskWire.message("stash-import");message.add("stash",p);message.addProperty("scope",SCOPE);message.addProperty("exportId",UUID.randomUUID().toString());StashCatalog.attachObservation(message,observation(0,box("minecraft:obsidian",64)));
        assert TaskWire.text(StashCatalog.acceptExport(root,"A",WORKER,message),"outcome").equals("accepted");cold(file);
        StashCatalog.record(root,"A",SCOPE,p,observation(0,box("minecraft:obsidian",128)));StashStore.clearCache();
        assert TaskWire.text(StashCatalog.acceptExport(root,"A",WORKER,message),"outcome").equals("accepted");
        assert StashCatalog.get(root,"A",SCOPE,"Export").getAsJsonObject("containers").getAsJsonObject("0,100,0").getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==128 : "A lost ACK cannot replay stale stock after a cold restart";
        JsonObject pair=observation(0,box("minecraft:obsidian",128));pair.addProperty("capacity",54);pair.add("parts",JsonParser.parseString("[{x:0,y:100,z:0},{x:1,y:100,z:0}]"));StashCatalog.record(root,"A",SCOPE,p,pair);
        assert TaskWire.text(cold(file).getAsJsonObject("containers").getAsJsonObject("1,100,0"),"status").equals("removed") : "Double-chest alias retirement must be in the same durable delta as its real observation";
    }
}
