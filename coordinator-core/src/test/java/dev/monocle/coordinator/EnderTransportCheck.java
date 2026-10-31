package dev.monocle.coordinator;

import com.google.gson.*;
import dev.monocle.client.systems.bots.BotLua;
import dev.monocle.client.systems.bots.BotWorkflows;
import java.util.*;

/** Execute the real durable Lua against inventory-sized server receipts. */
final class EnderTransportCheck {
    static void run(){
        for(int capacity:List.of(27,54))for(int free:List.of(10,27,34))for(int stock:List.of(0,16,34,50,54,88,200))for(boolean deferred:List.of(false,true))simulate(capacity,free,stock,deferred);
        for(int capacity:List.of(0,27,54))for(int free:List.of(10,34))for(int room:List.of(0,1,20,34,54,88,100))fillAllocation(capacity,free,250,room);
        parentAllocation(true);parentAllocation(false);
        unansweredDelivery(5);unansweredDelivery(2);
        returnedIncidental();
        deferredBufferedCargo();
        columnRotation();
        incidentalAllocation();
        changingCapacity(32);changingCapacity(34);
        JsonObject a=args();a.addProperty("type","StashBuffer");a.addProperty("operation","Inspect");a.addProperty("buffered",0);
        StashCatalog.bufferAction(a);
        for(String op:List.of("Take","wrong")){a.addProperty("operation",op);try{StashCatalog.bufferAction(a);throw new AssertionError("Invalid ender operation accepted");}catch(IllegalArgumentException expected){}}
    }
    private static void unansweredDelivery(int stowed){
        // Synthetic source: the actual durable player workflow must never drop after a failed TPA.
        JsonObject args=args();args.addProperty("destination","Player");args.addProperty("transferAll",false);args.addProperty("enderTransport",false);args.addProperty("count",5);args.addProperty("returnByDeath",true);
        args.addProperty("recipient","00000000-0000-0000-0000-000000000002");args.addProperty("recipientName","SyntheticUser");
        JsonObject back=args.deepCopy();back.remove("picks");back.remove("targetStash");args.add("returnStash",back);
        String script=new BotWorkflows(null).get("task-kit-delivery").script();JsonObject state=new JsonObject(),receipt=null;List<String> actions=new ArrayList<>();
        for(int step=0;step<8;step++){
            var decision=BotLua.next(script,state,args,receipt,null);state=JsonParser.parseString(decision.state().toString()).getAsJsonObject();JsonObject action=decision.action();String type=TaskWire.text(action,"type");actions.add(type);receipt=new JsonObject();
            switch(type){
                case "StashResupply" -> {receipt.addProperty("picked",5);receipt.addProperty("nextPick",1);receipt.addProperty("sourceExhausted",false);}
                case "Tpa" -> {assert action.get("timeoutTicks").getAsInt()==1200&&TaskWire.flag(action,"allowFailure");receipt.addProperty("ok",false);receipt.addProperty("detail","Synthetic unanswered request");receipt.addProperty("tpaTimedOut",true);}
                case "StashVisit" -> {assert !TaskWire.flag(action,"returnByDeath") : "Failed delivery cargo must return without being killed";assert TaskWire.flag(state,"tpaTimedOut") : "Structured timeout cause survives cleanup actions";}
                case "StashBuffer" -> {StashCatalog.bufferAction(action);assert TaskWire.text(action,"operation").equals("Stow")&&action.get("count").getAsInt()==5;receipt.addProperty("capacity",54);receipt.addProperty("moved",stowed);receipt.addProperty("buffered",stowed+3);}
                case "Fail" -> {assert actions.equals(List.of("StashResupply","Tpa","StashVisit","StashBuffer","Fail"));assert state.get("total").getAsInt()==0;return;}
                default -> throw new AssertionError("Unexpected unanswered-delivery action: "+action);
            }
        }
        throw new AssertionError("Unanswered delivery did not release its queue slot");
    }
    private static JsonObject args(){
        JsonObject a=JsonParser.parseString("{name:'Source',homeName:'source',dimension:'minecraft:the_end',minX:0,maxX:3,minY:64,maxY:80,minZ:0,maxZ:3,count:36,destination:'Stash',depositMode:'Carry',transferAll:true,enderTransport:true,kitTypeId:'00000000-0000-0000-0000-000000000001',kitExemplar:{'minecraft:obsidian':64},picks:[{x:0,y:64,z:0,slot:0,resource:'minecraft:shulker_box'}]}").getAsJsonObject();
        JsonObject target=a.deepCopy();target.remove("picks");target.remove("targetStash");target.addProperty("name","Destination");target.addProperty("homeName","destination");target.addProperty("dimension","minecraft:overworld");a.add("targetStash",target);return a;
    }
    private static void changingCapacity(int free){
        JsonObject args=args(),state=new JsonObject(),receipt=null;String script=new BotWorkflows(null).get("task-kit-delivery").script();
        int stock=250,left=stock,carried=0,buffered=0,delivered=0,partial=0;
        for(int step=0;step<500;step++){
            var d=BotLua.next(script,state,args,receipt,null);state=JsonParser.parseString(d.state().toString()).getAsJsonObject();JsonObject a=d.action();String type=TaskWire.text(a,"type");receipt=new JsonObject();
            if(type.equals("StashResupply")){
                assert carried==0;int n=Math.min(left,Math.min(free,a.get("count").getAsInt()));left-=n;carried=n;
                receipt.addProperty("picked",n);receipt.addProperty("nextPick",left==0?1:0);receipt.addProperty("sourceExhausted",left==0);
            }else if(type.equals("StashBuffer")){
                String op=TaskWire.text(a,"operation");assert a.get("buffered").getAsInt()==buffered;
                int capacity=op.equals("Store")&&buffered==0?27:54,n=0;
                if(op.equals("Store")){n=Math.min(carried,Math.min(capacity-buffered,a.get("count").getAsInt()));assert n>0;if(n<carried)partial++;carried-=n;buffered+=n;}
                else if(op.equals("Take")){n=Math.min(buffered,Math.min(free-carried,a.get("count").getAsInt()));assert n>0;carried+=n;buffered-=n;}
                receipt.addProperty("capacity",capacity);receipt.addProperty("moved",n);receipt.addProperty("buffered",buffered);
            }else if(type.equals("StashDeposit")){
                int n=a.get("count").getAsInt();assert n==carried;carried-=n;delivered+=n;receipt.addProperty("deposited",n);receipt.addProperty("missing",0);
            }else if(type.equals("Done")){
                assert partial>1&&left==0&&carried==0&&buffered==0&&delivered==stock;
                assert a.getAsJsonObject("result").get("delivered").getAsInt()==stock;return;
            }else assert Set.of("Wait","StashVisit").contains(type) : a;
            assert left+carried+buffered+delivered==stock : "Switching ender views cannot strand, lose or double-count cargo";
        }
        throw new AssertionError("Changing ender capacity did not finish");
    }
    private static void columnRotation(){
        List<String> ids=List.of("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002","00000000-0000-0000-0000-000000000003");
        JsonObject args=new JsonObject(),allocations=new JsonObject();args.add("kitTypeIds",new Gson().toJsonTree(ids));args.add("columnAllocations",allocations);
        allocations.add(ids.get(0),JsonParser.parseString("[{x:0,y:64,z:0,axis:'Vertical'},{x:2,y:64,z:0,axis:'Vertical'}]"));
        allocations.add(ids.get(1),JsonParser.parseString("[{x:4,y:64,z:0,axis:'Vertical'}]"));allocations.add(ids.get(2),JsonParser.parseString("[{x:6,y:64,z:0,axis:'Vertical'}]"));
        String script=new BotWorkflows(null).get("task-stash-transfer").script();JsonObject state=new JsonObject(),receipt=null;List<Integer> visits=new ArrayList<>();int current=0,firstVisits=0;
        for(int step=0;step<20;step++){
            var d=BotLua.next(script,state,args,receipt,null);state=JsonParser.parseString(d.state().toString()).getAsJsonObject();JsonObject a=d.action();receipt=new JsonObject();
            switch(TaskWire.text(a,"type")){
                case "KitTransferPlan" -> {
                    current=a.get("index").getAsInt();visits.add(current);
                    if(current==3){receipt.addProperty("sourceUnavailable",true);break;}
                    JsonObject plan=new JsonObject();plan.addProperty("kitTypeId",ids.get(current-1));plan.add("targetStash",new JsonObject());plan.add("returnStash",new JsonObject());receipt.add("args",plan);
                }
                case "Call" -> {
                    JsonObject plan=a.getAsJsonObject("args");assert plan.getAsJsonObject("targetStash").getAsJsonArray("allocatedColumns").size()==1&&TaskWire.flag(plan,"fillAllocatedColumns");
                    int x=plan.getAsJsonObject("targetStash").getAsJsonArray("allocatedColumns").get(0).getAsJsonObject().get("x").getAsInt();
                    JsonObject value=new JsonObject();value.addProperty("missing",0);
                    if(current==1&&firstVisits++==0){assert x==0;value.addProperty("destinationFull",true);value.addProperty("retained",0);value.addProperty("returned",2);value.addProperty("delivered",10);}
                    else{assert x==(current==1?2:4);value.addProperty("sourceExhausted",true);value.addProperty("delivered",current==1?4:3);}receipt.add("value",value);
                }
                case "Done" -> {
                    assert visits.equals(List.of(1,2,3,1)) : "Rotate after one allocated column and never revisit an exhausted source";
                    JsonObject result=a.getAsJsonObject("result");assert result.get("delivered").getAsInt()==17&&result.get("returned").getAsInt()==2;
                    assert !TaskWire.flag(result,"sourceExhausted")&&TaskWire.flag(result.getAsJsonObject("deferred"),ids.get(2)) : "Unavailable sources are reported separately from verified exhaustion";return;
                }
                default -> throw new AssertionError(a);
            }
        }
        throw new AssertionError("Column rotation did not finish");
    }
    private static void incidentalAllocation(){
        JsonObject args=args();args.getAsJsonObject("targetStash").add("allocatedColumns",JsonParser.parseString("[{x:0,y:64,z:0,axis:'Vertical'}]"));args.getAsJsonObject("targetStash").addProperty("fillAllocatedColumns",true);
        JsonObject state=JsonParser.parseString("{phase:'take',batch:0,extraIndex:1,incidental:[{kitTypeId:'00000000-0000-0000-0000-000000000002',count:1}],protectedBoxes:[]}").getAsJsonObject();
        JsonObject action=BotLua.next(new BotWorkflows(null).get("task-kit-delivery").script(),state,args,null,null).action();
        assert TaskWire.text(action,"type").equals("StashDeposit")&&!action.has("allocatedColumns")&&!action.has("fillAllocatedColumns") : "Incidental kits cannot inherit another kit's column allocation";
    }
    private static void simulate(int capacity,int free,int stock,boolean deferred){
        JsonObject args=args(),state=new JsonObject(),receipt=null;int left=stock,carried=0,buffer=0,delivered=0,maxCargo=0;List<Integer> deposits=new ArrayList<>();
        String script=new BotWorkflows(null).get("task-kit-delivery").script();
        for(int step=0;step<500;step++){
            var d=BotLua.next(script,state,args,receipt,null);
            state=JsonParser.parseString(d.state().toString()).getAsJsonObject();JsonObject a=d.action();String type=TaskWire.text(a,"type");receipt=new JsonObject();
            if(type.equals("Done")){
                assert left==0&&carried==0&&buffer==0&&delivered==stock : "Cargo was lost or counted twice";
                assert a.getAsJsonObject("result").get("delivered").getAsInt()==stock;
                if(deferred)assert a.getAsJsonObject("result").get("sourceDeferred").getAsBoolean();
                if(capacity==54&&free==34&&stock>=88)assert maxCargo==88&&deposits.subList(0,3).equals(List.of(34,34,20)) : "A 54-slot buffer drains in two inventory trips after the carried load";
                return;
            }
            if(type.equals("StashResupply")){
                assert carried==0;int n=Math.min(left,Math.min(free,a.get("count").getAsInt()));left-=n;carried+=n;
                receipt.addProperty("picked",n);receipt.addProperty("nextPick",left==0?1:0);receipt.addProperty("sourceExhausted",left==0&&!deferred);
                if(deferred){receipt.addProperty("sourceDeferred",true);receipt.add("deferredSources",JsonParser.parseString("['Earlier hopper stock unresolved']"));}
            }else if(type.equals("StashBuffer")){
                StashCatalog.bufferAction(a);String op=TaskWire.text(a,"operation");assert a.get("buffered").getAsInt()==buffer;
                int n=op.equals("Store")?Math.min(carried,Math.min(capacity-buffer,a.get("count").getAsInt())):op.equals("Take")?Math.min(buffer,Math.min(free-carried,a.get("count").getAsInt())):0;
                if(op.equals("Store")){carried-=n;buffer+=n;}else if(op.equals("Take")){carried+=n;buffer-=n;assert TaskWire.text(a,"dimension").equals("minecraft:overworld");}
                receipt.addProperty("capacity",capacity);receipt.addProperty("moved",n);receipt.addProperty("buffered",buffer);
            }else if(type.equals("StashDeposit")){
                int n=a.get("count").getAsInt();assert n<=carried&&n<=36;carried-=n;delivered+=n;deposits.add(n);receipt.addProperty("deposited",n);receipt.addProperty("missing",0);
            }else assert Set.of("Wait","StashVisit").contains(type) : a;
            maxCargo=Math.max(maxCargo,carried+buffer);assert delivered+left+carried+buffer==stock;
        }
        throw new AssertionError("Ender transfer did not finish: "+state);
    }
    private static void fillAllocation(int capacity,int free,int stock,int room){
        JsonObject args=args();args.addProperty("fillAllocatedColumns",true);args.getAsJsonObject("targetStash").addProperty("fillAllocatedColumns",true);
        JsonObject back=args.deepCopy();back.remove("picks");back.remove("targetStash");back.remove("fillAllocatedColumns");args.add("returnStash",back);
        if(capacity==0)args.remove("enderTransport");
        JsonObject state=new JsonObject(),receipt=null;int source=stock,carried=0,buffer=0,delivered=0,returned=0;
        String script=new BotWorkflows(null).get("task-kit-delivery").script();
        for(int step=0;step<500;step++){
            var decision=BotLua.next(script,state,args,receipt,null);state=JsonParser.parseString(decision.state().toString()).getAsJsonObject();
            JsonObject a=decision.action();String type=TaskWire.text(a,"type");receipt=new JsonObject();
            if(type.equals("Done")){
                JsonObject result=a.getAsJsonObject("result");assert delivered==room&&carried==0&&buffer==0&&source==stock-delivered;
                assert result.get("destinationFull").getAsBoolean()&&!result.get("sourceExhausted").getAsBoolean()&&result.get("retained").getAsInt()==0;
                assert result.get("delivered").getAsInt()==delivered&&result.get("returned").getAsInt()==returned;
                return;
            }
            if(type.equals("StashResupply")){
                assert carried==0;int n=Math.min(source,Math.min(free,a.get("count").getAsInt()));source-=n;carried+=n;
                receipt.addProperty("picked",n);receipt.addProperty("nextPick",source==0?1:0);receipt.addProperty("sourceExhausted",source==0);
            }else if(type.equals("StashBuffer")){
                assert capacity>0;StashCatalog.bufferAction(a);String op=TaskWire.text(a,"operation");assert a.get("buffered").getAsInt()==buffer;
                int n=op.equals("Store")?Math.min(carried,Math.min(capacity-buffer,a.get("count").getAsInt())):op.equals("Take")?Math.min(buffer,Math.min(free-carried,a.get("count").getAsInt())):0;
                if(op.equals("Store")){carried-=n;buffer+=n;}else if(op.equals("Take")){carried+=n;buffer-=n;}
                receipt.addProperty("capacity",capacity);receipt.addProperty("moved",n);receipt.addProperty("buffered",buffer);
            }else if(type.equals("StashDeposit")){
                int wanted=a.get("count").getAsInt();assert wanted==carried&&wanted<=36;
                boolean returning=TaskWire.text(a,"name").equals("Source");
                int n=returning?wanted:Math.min(wanted,room-delivered);carried-=n;
                receipt.addProperty("deposited",n);receipt.addProperty("missing",0);
                if(returning){assert !TaskWire.flag(a,"fillAllocatedColumns")&&TaskWire.text(a,"dimension").equals("minecraft:the_end");source+=n;returned+=n;}
                else{delivered+=n;if(delivered==room){receipt.addProperty("columnFull",true);receipt.addProperty("retained",carried);}}
            }else if(type.equals("StashVisit"))assert TaskWire.text(a,"name").equals("Source")||TaskWire.text(a,"name").equals("Destination");
            else assert type.equals("Wait") : a;
            assert source+carried+buffer+delivered==stock : "Overflow return must conserve cargo without counting source deposits as deliveries";
        }
        throw new AssertionError("Column filling did not finish: "+state);
    }
    private static void parentAllocation(boolean enabled){
        String first="00000000-0000-0000-0000-000000000001",second="00000000-0000-0000-0000-000000000002";
        JsonObject args=new JsonObject();args.add("kitTypeIds",new Gson().toJsonTree(List.of(first,second)));args.addProperty("fillAllocatedColumns",enabled);
        JsonObject state=new JsonObject(),receipt=null;String script=new BotWorkflows(null).get("task-stash-transfer").script();int index=0;
        for(int step=0;step<20;step++){
            var d=BotLua.next(script,state,args,receipt,null);state=d.state();JsonObject a=d.action();String type=TaskWire.text(a,"type");receipt=new JsonObject();
            if(type.equals("KitTransferPlan")){index=a.get("index").getAsInt();JsonObject plan=new JsonObject();plan.addProperty("kitTypeId",index==1?first:second);receipt.add("args",plan);receipt.addProperty("carried",0);}
            else if(type.equals("Call")){
                JsonObject value=new JsonObject();value.addProperty("delivered",index==1?5:2);value.addProperty("missing",0);value.addProperty("sourceExhausted",index!=1);
                if(index==1){value.addProperty("destinationFull",true);value.addProperty("retained",0);value.addProperty("returned",32);}receipt.add("value",value);
            }else if(type.equals("Done")){
                assert enabled;JsonObject value=a.getAsJsonObject("result");assert value.get("delivered").getAsInt()==7&&value.get("returned").getAsInt()==32;
                assert !value.get("sourceExhausted").getAsBoolean()&&value.getAsJsonObject("destinationLimited").get(first).getAsBoolean();return;
            }else if(type.equals("Fail")){assert !enabled : a;return;}
            else throw new AssertionError(a);
        }
        throw new AssertionError("Parent transfer did not handle allocation completion");
    }
    private static void deferredBufferedCargo(){
        JsonObject args=args(),state=JsonParser.parseString("{phase:'taking',all:true,left:36,tripCount:36,cursor:0,total:88,buffered:34,bufferCapacity:54}").getAsJsonObject();
        JsonObject receipt=JsonParser.parseString("{picked:7,nextPick:1,sourceExhausted:false,sourceDeferred:true,deferredSources:['Hopper stock unresolved']}").getAsJsonObject();
        String script=new BotWorkflows(null).get("task-kit-delivery").script();int carried=7,buffered=34,deposited=0;
        for(int step=0;step<12;step++){
            var d=BotLua.next(script,state,args,receipt,null);state=d.state();JsonObject a=d.action();receipt=new JsonObject();
            switch(TaskWire.text(a,"type")){
                case "StashDeposit" -> {int n=a.get("count").getAsInt();assert n==carried;deposited+=n;carried=0;receipt.addProperty("deposited",n);receipt.addProperty("missing",0);}
                case "StashBuffer" -> {assert TaskWire.text(a,"operation").equals("Take") : "Deferred source cargo must be delivered, not buffered before completion";assert a.get("buffered").getAsInt()==buffered;carried=buffered;buffered=0;receipt.addProperty("capacity",54);receipt.addProperty("moved",carried);receipt.addProperty("buffered",0);}
                case "Done" -> {JsonObject result=a.getAsJsonObject("result");assert carried==0&&buffered==0&&deposited==41;assert result.get("delivered").getAsInt()==129&&result.get("sourceDeferred").getAsBoolean();return;}
                default -> throw new AssertionError("Unexpected action during deferred cargo drain: "+a);
            }
        }
        throw new AssertionError("Deferred source cargo did not drain");
    }
    private static void returnedIncidental(){
        JsonObject args=args();args.addProperty("fillAllocatedColumns",true);JsonObject back=args.deepCopy();back.remove("targetStash");back.remove("picks");args.add("returnStash",back);
        JsonObject state=JsonParser.parseString("{phase:'capacity-return',buffered:0,returnBatch:2,tripCount:36,total:5,extraIndex:1,incidental:[{kitTypeId:'00000000-0000-0000-0000-000000000002',count:1,kitExemplar:{'minecraft:stone':64},includeIncomplete:false,incomplete:false}],protectedBoxes:[]}").getAsJsonObject(),receipt=null;
        String script=new BotWorkflows(null).get("task-kit-delivery").script();
        for(int expected:List.of(2,1)){
            var d=BotLua.next(script,state,args,receipt,null);state=d.state();JsonObject a=d.action();assert TaskWire.text(a,"type").equals("StashDeposit")&&TaskWire.text(a,"name").equals("Source")&&a.get("count").getAsInt()==expected;
            if(expected==1)assert TaskWire.text(a,"kitTypeId").endsWith("0002");receipt=new JsonObject();receipt.addProperty("deposited",expected);receipt.addProperty("missing",0);
        }
        var done=BotLua.next(script,state,args,receipt,null);assert TaskWire.text(done.action(),"type").equals("Done");JsonObject result=done.action().getAsJsonObject("result");
        assert result.get("delivered").getAsInt()==5&&result.get("returned").getAsInt()==3 : "A full allocation must also return incidental cargo before advancing";
    }
}
