package dev.monocle.client.systems.bots;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Small native-work library: repeating work capabilities and ordered, on-demand supply fallbacks. */
public final class BotWorkflows {
    private static String checkedName(String value) { String name=value.strip(); if(name.isEmpty() || name.length()>48 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Name must be 1–48 printable characters"); return name; }
    public static final String DEFAULT_ID = "highway-default";
    public enum Action { Excavating, Paving, InventoryShulkers, EnderChestContents, EnderChestFarm, Call }
    public record Step(Action action, String target) {
        public Step { Objects.requireNonNull(action); target = target == null ? "" : target; }
    }
    public record Workflow(String id, String name, String folder, boolean builtin, List<Step> steps, String script, List<String> dependencies, List<String> profiles) {
        public Workflow { steps = List.copyOf(steps); script = script == null ? "" : script; dependencies = List.copyOf(dependencies); profiles = List.copyOf(profiles); }
        public Workflow(String id, String name, String folder, boolean builtin, List<Step> steps) { this(id, name, folder, builtin, steps, "", List.of(), List.of()); }
        @Override public String toString() { return name; }
    }
    private final Path file;
    private final Map<String, Workflow> entries = builtins();
    private boolean loaded;
    private String failure;

    public BotWorkflows(Path file) { this.file = file; loaded = file == null; }
    private static Map<String, Workflow> builtins() {
        Map<String, Workflow> values = new LinkedHashMap<>();
        addBuiltin(values, "highway-supplies", "Carried supplies", List.of(new Step(Action.InventoryShulkers, ""),
            new Step(Action.EnderChestContents, "")));
        addBuiltin(values, DEFAULT_ID, "6b6t Highway Builder", List.of(new Step(Action.Excavating, ""), new Step(Action.Paving, ""), new Step(Action.Call, "highway-supplies")));
        addBuiltin(values, "highway-excavate", "Excavation crew", List.of(new Step(Action.Excavating, ""), new Step(Action.Call, "highway-supplies")));
        addBuiltin(values, "highway-pave", "Paving crew", List.of(new Step(Action.Paving, ""), new Step(Action.Call, "highway-supplies")));
        for (var entry : Map.of("follow", "Follow crewmate", "travel", "Travel to coordinates", "drop", "Drop items", "tpa", "TPA rendezvous", "wait", "Wait", "modules", "Run configured modules", "profile", "Set profile").entrySet()) {
            String argument = entry.getKey().equals("wait") ? "ctx.args.ticks or 20" : entry.getKey().equals("profile") ? "ctx.args.name or 'Current'" : "ctx.args";
            String script = "return function(ctx)\n  if ctx.state.started then return bot.done() end\n  ctx.state.started = true\n  return bot." + entry.getKey() + "(" + argument + ")\nend\n";
            String id = "task-" + entry.getKey();
            values.put(id, new Workflow(id, entry.getValue(), "Common Tasks", true, List.of(), script, List.of(), List.of("Current")));
        }
        values.put("task-bodyguard", new Workflow("task-bodyguard", "Bodyguard", "Common Tasks", true, List.of(),
            "return function(ctx)\n  if ctx.state.started then return bot.done() end\n  ctx.state.started = true\n  ctx.args.bodyguard = true\n  return bot.follow(ctx.args)\nend\n", List.of(), List.of("Current")));
        values.put("task-crystal-guard", new Workflow("task-crystal-guard", "Crystal Guard", "Combat", true, List.of(),
            "return function(ctx)\n  if ctx.state.started then return bot.done() end\n  ctx.state.started = true\n  ctx.args.bodyguard = true\n  ctx.args.crystalGuard = true\n  return bot.follow(ctx.args)\nend\n", List.of(), List.of("Current")));
        values.put("task-stash-hunt", new Workflow("task-stash-hunt", "Distributed stash hunt", "Stash Hunting", true, List.of(),
            "return function(ctx)\n  if ctx.state.started then return bot.done() end\n  ctx.state.started = true\n  return bot.stash_hunt(ctx.args)\nend\n", List.of(), List.of("Current")));
        values.put("task-stash-scan", new Workflow("task-stash-scan", "Inspect stash", "Stash Management", true, List.of(),
            "return function(ctx)\n if ctx.args.scanAnchor and not ctx.state.rendezvoused then\n  ctx.state.rendezvoused=true\n  return bot.tpa({target=ctx.args.scanAnchor,allowFailure=true})\n end\n if ctx.state.started then return bot.done(ctx.result) end\n ctx.state.started=true\n return bot.stash_scan(ctx.args)\nend", List.of(), List.of("Current")));
        values.put("task-stash-resupply",new Workflow("task-stash-resupply","Resupply from stash","Stash Management",true,List.of(),
            "return function(ctx)\n if not ctx.state.loaded then ctx.state.loaded=true; return bot.stash_resupply(ctx.args) end\n if not ctx.state.returning then ctx.state.returning=true; return bot.tpa({target=ctx.args.target,warmupTicks=ctx.args.warmupTicks,acceptDelayTicks=ctx.args.acceptDelayTicks,timeoutTicks=1200}) end\n return bot.done(ctx.result)\nend",List.of(),List.of("Current")));
        String depositReceipt="""
            local function depositCount(r,requested)
             local n=requested; local missing=0
             if r and r.deposited~=nil then n=r.deposited end
             if r and r.missing~=nil then missing=r.missing end
             if type(n)~='number' or type(missing)~='number' or n<0 or missing<0 or n~=math.floor(n) or missing~=math.floor(missing) or n+missing~=requested then error('Invalid kit deposit receipt') end
             return n,missing
            end
            """;
        values.put("task-stash-sort",new Workflow("task-stash-sort","Sort stash categories","Stash Management",true,List.of(),"""
            return function(ctx)
             local s=ctx.state
             if not s.phase then s.phase='plan';s.sequence=0;return bot.stash_sort_plan({sequence=0}) end
             if s.phase=='action' then s.phase='plan';return bot.stash_sort_plan({sequence=s.sequence,receipt=ctx.result or {}}) end
             local r=ctx.result
             if not r or type(r.sequence)~='number' then return bot.fail('Invalid sorting instruction') end
             s.sequence=r.sequence
             if r.done then return bot.done({moved=r.moved,batches=r.batches}) end
             if r.probe then return bot.stash_sort_plan({sequence=s.sequence,receipt={freeSlots=r.freeSlots,shulkers=r.shulkers}}) end
             if not r.action then return bot.fail('Missing sorting action') end
             s.phase='action'
             if r.action.type=='StashScan' then return bot.stash_scan(r.action) end
             if r.action.type=='StashResupply' then return bot.stash_resupply(r.action) end
             if r.action.type=='StashDeposit' then return bot.stash_store(r.action) end
             if r.action.type=='Wait' then return bot.wait(r.action.ticks) end
             return bot.fail('Unsupported sorting action')
            end
            """,List.of(),List.of("Current")));
        String kitScript=depositReceipt+"""
            local function enderAction(args,at,operation,buffered,count)
             local a={operation=operation,buffered=buffered,count=count}
             if operation=='Stow' then a.destination='Player' end
             for _,k in ipairs({'name','homeName','homeWarmupTicks','homeCooldownTicks','dimension','minX','maxX','minY','maxY','minZ','maxZ'}) do a[k]=at[k] end
             for _,k in ipairs({'kitTypeId','kitExemplar','kitIdentities','includeIncomplete','incomplete'}) do a[k]=args[k] end
             return bot.stash_buffer(a)
            end
            local function enderReceipt(r,before,requested,take,inspect)
             if not r or (r.capacity~=27 and r.capacity~=54) or type(r.moved)~='number' or r.moved<0 or r.moved>requested or r.moved~=math.floor(r.moved) or r.buffered~=before+(take and -r.moved or r.moved) or r.buffered<0 or r.buffered>r.capacity or (inspect and (r.moved~=0 or r.buffered~=0)) or (not inspect and r.moved==0) then error('Invalid ender cargo receipt') end
             return r
            end
            local function returnVisit(at,death)
             local back={};for k,v in pairs(at) do back[k]=v end
             back.returnByDeath=death or false;return bot.stash_visit(back)
            end
            return function(ctx)
             local s=ctx.state
             if s.phase=='capacity-arrive' then s.atReturn=true;s.phase='capacity-return' end
             if s.phase=='capacity-take' then
              local r=enderReceipt(ctx.result,s.buffered,s.tripCount,true,false)
              s.buffered=r.buffered;s.returnBatch=r.moved;s.phase='capacity-return'
             end
             if s.phase=='capacity-store' then
              local n,missing=depositCount(ctx.result,s.returnBatch)
              s.returned=(s.returned or 0)+n;s.missing=(s.missing or 0)+missing;s.returnBatch=0;s.atReturn=true;s.phase='capacity-return'
             end
             if s.phase=='capacity-extra' then
              local n,missing=depositCount(ctx.result,s.incidental[s.extraIndex].count)
              s.returned=(s.returned or 0)+n;s.missing=(s.missing or 0)+missing;s.extraIndex=s.extraIndex+1;s.phase='capacity-return'
             end
             if s.phase=='capacity-return' then
              if not ctx.args.fillAllocatedColumns or not ctx.args.returnStash then return bot.fail('Missing column-overflow return route') end
              if (s.returnBatch or 0)>0 then
               local store={};for k,v in pairs(ctx.args.returnStash) do store[k]=v end
               for _,k in ipairs({'kitTypeId','kitExemplar','kitIdentities','incomplete','includeIncomplete','catalogCrew'}) do store[k]=ctx.args[k] end
               store.type='StashDeposit';store.count=s.returnBatch;s.phase='capacity-store';return bot.stash_store(store)
              end
              if (s.buffered or 0)>0 then
               if not s.atReturn then s.phase='capacity-arrive';return returnVisit(ctx.args.returnStash,false) end
               s.phase='capacity-take';return enderAction(ctx.args,ctx.args.returnStash,'Take',s.buffered,s.tripCount)
              end
              if s.incidental and s.extraIndex<=#s.incidental then
               local store={};for k,v in pairs(ctx.args.returnStash) do store[k]=v end
               for _,k in ipairs({'kitTypeId','kitExemplar','count','includeIncomplete','incomplete'}) do store[k]=s.incidental[s.extraIndex][k] end
               store.type='StashDeposit';store.protectedBoxes=s.protectedBoxes;s.phase='capacity-extra';return bot.stash_store(store)
              end
              return bot.done({delivered=s.total,missing=s.missing or 0,destinationFull=true,retained=0,returned=s.returned or 0,sourceExhausted=false})
             end
             if s.phase=='returning' then
              if ctx.result and ctx.result.ok==false then return bot.fail('Courier return failed; '..(ctx.result.detail or 'check the worker')) end
              if s.deliveryFailure then
               if (s.returnCount or 0)>0 then
                s.phase='return-stowing';return enderAction(ctx.args,ctx.args.returnStash,'Stow',0,s.returnCount)
               end
               return bot.fail('Delivery incomplete; returned to source: '..s.deliveryFailure)
              end
              return bot.done({delivered=s.total,missing=s.missing or 0,returnedHome=true})
             end
             if s.phase=='return-stowing' then
              local r=ctx.result
              if not r or type(r.moved)~='number' or r.moved~=s.returnCount or (r.capacity~=27 and r.capacity~=54) or type(r.buffered)~='number' or r.buffered<r.moved or r.buffered>r.capacity then return bot.fail('Undelivered kits could not all be secured in the ender chest; inspect retained cargo') end
              s.returned=r.moved;return bot.fail('Delivery incomplete; secured '..r.moved..' kits in the ender chest. Requester cooldown retained.')
             end
             -- Legacy checkpoints may already be reconciling a source deposit.
             if s.phase=='return-depositing' then
              local n,missing=depositCount(ctx.result,s.returnCount)
              return bot.fail('Delivery incomplete; returned to source and redeposited '..n..' kits ('..missing..' missing): '..s.deliveryFailure)
             end
             if not s.phase then
              s.phase='take'; s.left=ctx.args.count; s.tripCount=ctx.args.count; s.cursor=ctx.args.oneBatch and (ctx.args.pickIndex or 0) or 0; s.all=ctx.args.transferAll or false; s.total=0
              if ctx.args.enderTransport then
               if not s.all or ctx.args.destination~='Stash' or ctx.args.oneBatch or ctx.args.targetStash.targetColumn then return bot.fail('Ender transport requires a sequential until-empty stash transfer') end
               s.phase='buffer-inspect';return enderAction(ctx.args,ctx.args,'Inspect',0,ctx.args.count)
              end
             end
             if s.phase=='buffer-inspect' then
              local r=enderReceipt(ctx.result,0,0,false,true);s.bufferCapacity=r.capacity;s.buffered=0;s.phase='take'
             elseif s.phase=='buffer-store' then
              local r=enderReceipt(ctx.result,s.buffered,s.batch,false,false)
              s.buffered=r.buffered;s.bufferCapacity=r.capacity
              if r.moved==s.batch then s.batch=nil;s.phase='take'
              else s.batch=s.batch-r.moved;s.bufferStoreLimited=true;s.phase='deliver' end
             elseif s.phase=='buffer-take' then
              local r=enderReceipt(ctx.result,s.buffered,s.tripCount,true,false)
              s.buffered=r.buffered;s.bufferCapacity=r.capacity;s.batch=r.moved;s.phase='deliver'
             elseif s.phase=='buffer-arrive' then s.atDestination=true;s.phase='take' end
             if s.phase=='taking' then
              local r=ctx.result
              if not r or not r.picked or not r.nextPick or r.picked<0 or r.picked>(s.all and ctx.args.count or s.left) or r.nextPick<s.cursor or r.nextPick>#ctx.args.picks or (r.sourceExhausted and (r.nextPick~=#ctx.args.picks or r.sourceDeferred)) or (r.sourceDeferred and (type(r.deferredSources)~='table' or #r.deferredSources==0 or #r.deferredSources>54)) or (r.picked==0 and not (r.inventoryFull and r.incidental and #r.incidental>0) and not (s.all and (r.sourceExhausted or (r.sourceDeferred and r.nextPick==#ctx.args.picks)))) then return bot.fail('Invalid kit pickup receipt') end
              s.incidental=r.incidental or {}; s.protectedBoxes=r.protectedBoxes or {}; s.extraIndex=1; s.inventoryFull=r.inventoryFull or false
              if type(s.incidental)~='table' or #s.incidental>36 or (#s.incidental>0 and not ctx.args.targetStash) then return bot.fail('Invalid incidental kit receipt') end
              local extras=0
              for _,extra in ipairs(s.incidental) do
               local known=false; for _,id in ipairs(ctx.args.knownKitTypeIds or {}) do if extra.kitTypeId==id then known=true end end
               if not known or type(extra.count)~='number' or extra.count<1 or extra.count>36 or extra.count~=math.floor(extra.count) or extra.kitTypeId==ctx.args.kitTypeId then return bot.fail('Invalid incidental kit count') end
               extras=extras+extra.count
              end
              if extras+r.picked>36 then return bot.fail('Incidental kits exceed inventory capacity') end
              if r.sourceDeferred then
               for _,note in ipairs(r.deferredSources) do if type(note)~='string' or #note>2048 then return bot.fail('Invalid deferred source receipt') end end
               s.deferred=r.deferredSources
              end
              if not s.all then s.left=s.left-r.picked end
              s.batch=r.picked; s.cursor=r.nextPick; s.exhausted=r.sourceExhausted or false; s.issue=r.sourceIssue
              if s.batch==0 and #s.incidental==0 and (s.buffered or 0)==0 then
               if ctx.args.oneBatch then return bot.done({delivered=0,missing=0,picked=0,nextPick=s.cursor,sourceExhausted=s.exhausted,sourceDeferred=s.deferred~=nil,deferredSources=s.deferred}) end
               if s.deferred then return bot.done({delivered=s.total,missing=s.missing or 0,sourceExhausted=false,sourceDeferred=true,deferredSources=s.deferred}) end
               s.emptyPasses=(s.emptyPasses or 0)+1
               if s.emptyPasses>=2 then return bot.done({delivered=s.total,missing=s.missing or 0,sourceExhausted=true}) end
               s.cursor=0; s.exhausted=false; s.phase='take'
               return bot.wait(20)
              end
              s.emptyPasses=0
              if ctx.args.destination=='Carry' and s.left>0 then return bot.fail('Carry destination has no room for the remaining kits') end
              s.phase=(ctx.args.destination=='Ender Chest' or ctx.args.destination=='Carry') and 'take' or 'deliver'
              if s.phase=='take' then s.total=s.total+s.batch end
             end
             if s.phase=='storing' then
              if ctx.result and ctx.result.columnFull==true then
               local r=ctx.result; local n=r.deposited; local missing=r.missing or 0; local retained=r.retained
               if not ctx.args.targetStash or not (ctx.args.targetStash.targetColumn or ctx.args.fillAllocatedColumns and ctx.args.returnStash) or type(n)~='number' or type(missing)~='number' or type(retained)~='number' or n<0 or missing<0 or retained<0 or n~=math.floor(n) or missing~=math.floor(missing) or retained~=math.floor(retained) or n+missing+retained~=s.batch then return bot.fail('Invalid full-column deposit receipt') end
               if ctx.args.fillAllocatedColumns then
                s.total=s.total+n;s.missing=(s.missing or 0)+missing;s.returnBatch=retained;s.phase='capacity-return';return bot.wait(0)
               end
               return bot.done({delivered=s.total+n,missing=(s.missing or 0)+missing,retained=retained,columnFull=true,sourceExhausted=false})
              end
              local n,missing=depositCount(ctx.result,s.batch)
              s.total=s.total+n; s.missing=(s.missing or 0)+missing; s.phase='take'
              if ctx.args.enderTransport then s.atDestination=true end
             end
             if s.phase=='dropping' then
              if ctx.result and ctx.result.ok==false and ctx.result.dropped==nil then return bot.fail('Courier failure lacks confirmed drop counts; inspect inventory') end
              local n=ctx.result and ctx.result.dropped or s.batch
              if type(n)~='number' or n<0 or n>s.batch or n~=math.floor(n) then return bot.fail('Invalid courier drop receipt') end
              s.total=s.total+n;s.phase='take'
              if ctx.result and ctx.result.ok==false then
               if not ctx.args.returnStash then return bot.fail(ctx.result.detail or 'Safe handoff failed') end
               s.returnCount=s.batch-n;s.deliveryFailure=ctx.result.detail or 'Safe handoff failed';s.phase='returning';return returnVisit(ctx.args.returnStash,false)
              end
              if n~=s.batch then return bot.fail('Incomplete courier drop receipt') end
             end
             if s.phase=='extra-storing' then
              local extra=s.incidental[s.extraIndex]; local n,missing=depositCount(ctx.result,extra.count)
              s.extraDelivered=(s.extraDelivered or 0)+n; s.extraMissing=(s.extraMissing or 0)+missing; s.extraIndex=s.extraIndex+1; s.phase='take'
             end
             if s.phase=='take' and s.batch~=nil and s.incidental and s.extraIndex<=#s.incidental then
              local extra=s.incidental[s.extraIndex]; local target={}
              for k,v in pairs(ctx.args.targetStash) do if k~='targetColumn' and k~='columnKitTypeIds' and k~='fillAllocatedColumns' and k~='allocatedColumns' then target[k]=v end end
              for _,k in ipairs({'kitTypeId','kitExemplar','count','includeIncomplete','incomplete'}) do target[k]=extra[k] end
              target.protectedBoxes=s.protectedBoxes
              s.phase='extra-storing'; return bot.stash_store(target)
             end
             if s.phase=='teleporting' then
              if ctx.result and ctx.result.ok==false then
               if not ctx.args.returnStash then return bot.fail(ctx.result.detail or 'Recipient TPA failed') end
               s.tpaTimedOut=ctx.result.tpaTimedOut==true;s.returnCount=s.batch;s.deliveryFailure=ctx.result.detail or 'Recipient TPA failed';s.phase='returning';return returnVisit(ctx.args.returnStash,false)
              end
              s.phase='dropping'
              return bot.drop({item=ctx.args.kitItem,kitTypeId=ctx.args.kitTypeId,kitExemplar=ctx.args.kitExemplar,kitIdentities=ctx.args.kitIdentities,incomplete=ctx.args.incomplete,count=s.batch,recipient=ctx.args.recipient,handoff=true,deliveryAnchor=ctx.result and ctx.result.deliveryAnchor,allowFailure=ctx.args.returnStash~=nil})
             end
             if s.phase=='deliver' then
              if ctx.args.enderTransport and not s.atDestination and not s.bufferStoreLimited and s.batch>0 and s.buffered<s.bufferCapacity and not s.exhausted and (not s.deferred or s.cursor<#ctx.args.picks) and not s.issue and #s.incidental==0 then
               s.phase='buffer-store';return enderAction(ctx.args,ctx.args,'Store',s.buffered,s.batch)
              end
              if ctx.args.destination=='Player' then
               s.phase='teleporting'
               return bot.tpa({target=ctx.args.recipient,targetName=ctx.args.recipientName,external=true,radius=4,timeoutTicks=1200,allowFailure=ctx.args.returnStash~=nil})
              end
              if s.batch==0 then
               if (s.buffered or 0)>0 and not s.atDestination then s.phase='buffer-arrive';return bot.stash_visit(ctx.args.targetStash) end
               s.phase='take'; return bot.wait(0)
              end
              s.phase='storing'; ctx.args.targetStash.count=s.batch
              return bot.stash_store(ctx.args.targetStash)
             end
             if s.phase=='take' and s.atDestination and (s.buffered or 0)>0 then
              s.phase='buffer-take';return enderAction(ctx.args,ctx.args.targetStash,'Take',s.buffered,s.tripCount)
             end
             if s.phase=='take' then s.atDestination=false;s.bufferStoreLimited=nil end
             if s.issue then return bot.fail('Delivered '..s.total..' kits before source needs attention: '..s.issue) end
             if ctx.args.oneBatch and s.batch~=nil and s.phase=='take' then return bot.done({delivered=s.total,missing=s.missing or 0,picked=s.batch,nextPick=s.cursor,sourceExhausted=s.exhausted,sourceDeferred=s.deferred~=nil,deferredSources=s.deferred,inventoryFull=s.inventoryFull,incidentalDelivered=s.extraDelivered or 0,incidentalMissing=s.extraMissing or 0}) end
             if s.all and s.deferred and s.cursor==#ctx.args.picks then return bot.done({delivered=s.total,missing=s.missing or 0,sourceExhausted=false,sourceDeferred=true,deferredSources=s.deferred}) end
             if s.left==0 then
              if ctx.args.returnStash then s.phase='returning';return returnVisit(ctx.args.returnStash,ctx.args.returnByDeath) end
              return bot.done({delivered=s.total,missing=s.missing or 0,sourceExhausted=s.exhausted})
             end
             if s.all and s.exhausted then s.cursor=0; s.exhausted=false end
             s.phase='taking'; ctx.args.count=s.all and (s.tripCount or ctx.args.count) or s.left; ctx.args.pickIndex=s.cursor
             if ctx.args.enderTransport and s.buffered<s.bufferCapacity then ctx.args.count=math.min(ctx.args.count,s.bufferCapacity-s.buffered) end
             return bot.stash_resupply(ctx.args)
            end
            """;
        values.put("task-kit-delivery",new Workflow("task-kit-delivery","Kit Delivery","Stash Management",true,List.of(),kitScript,List.of(),List.of("Current")));
        values.put("task-kit-remove-incomplete",new Workflow("task-kit-remove-incomplete","Remove Incomplete Kits","Stash Management",true,List.of(),kitScript,List.of(),List.of("Current")));
        String roundRobin="""
            local function whole(n,max)
             return type(n)=='number' and n>=0 and n<=max and n==math.floor(n)
            end
            local function routeStep(ctx)
             local s=ctx.state; local routes=ctx.args.routes
             if not s.index then
              s.index=1; s.total=0; s.missing=0; s.recovered=0; s.preparing=ctx.args.depositCarried or false; s.routes={}
              for i,_ in ipairs(routes) do s.routes[i]={cursor=0,empty=0,delivered=0,missing=0,recovered=0,stalls=0} end
             end
             local progress=s.routes[s.index]
             if s.phase=='planned' then
              local plan=ctx.result and ctx.result.args; local carried=ctx.result and ctx.result.carried or 0
              if not plan or plan.kitTypeId~=routes[s.index].kitTypeId or plan.oneBatch~=true or not whole(carried,36) or not plan.picks or #plan.picks<1 or #plan.picks>(plan.compactPicks and 1024 or 54) then return bot.fail('Invalid Round Robin plan') end
              if s.preparing then
               if carried>0 then
                s.phase='recovering'; s.recoverCount=carried; plan.targetStash.count=carried
                return bot.stash_store(plan.targetStash)
               end
               s.index=s.index+1
              else
               if not whole(progress.cursor,#plan.picks) then return bot.fail('Round Robin source cursor changed') end
               s.phase='delivering'; s.pickCount=#plan.picks; plan.pickIndex=progress.cursor
               return bot.call('task-kit-delivery',plan)
              end
             end
             if s.phase=='recovering' then
              local n,missing=depositCount(ctx.result,s.recoverCount)
              progress.delivered=progress.delivered+n; progress.missing=progress.missing+missing; progress.recovered=progress.recovered+n
              s.total=s.total+n; s.missing=s.missing+missing; s.recovered=s.recovered+n; s.index=s.index+1
             end
             if s.phase=='delivering' then
              local r=ctx.result and ctx.result.value
              if not r or not whole(r.picked,ctx.args.count) or not whole(r.delivered,r.picked) or not whole(r.missing or 0,r.picked) or r.delivered+(r.missing or 0)~=r.picked or not whole(r.nextPick,s.pickCount) or r.nextPick<progress.cursor or type(r.sourceExhausted)~='boolean' or (r.sourceExhausted and (r.nextPick~=s.pickCount or r.sourceDeferred)) or (r.sourceDeferred and (type(r.deferredSources)~='table' or #r.deferredSources<1 or #r.deferredSources>54)) or (r.picked==0 and not (r.inventoryFull and whole(r.incidentalDelivered,36) and r.incidentalDelivered>0) and (r.nextPick~=s.pickCount or not (r.sourceExhausted or r.sourceDeferred))) then return bot.fail('Invalid Round Robin batch receipt') end
              progress.delivered=progress.delivered+r.delivered; progress.missing=progress.missing+(r.missing or 0)
              s.total=s.total+r.delivered; s.missing=s.missing+(r.missing or 0)
              progress.cursor=r.nextPick==s.pickCount and 0 or r.nextPick
              if r.picked>0 or (r.incidentalDelivered or 0)>0 then progress.empty=0; progress.stalls=0
              elseif r.sourceDeferred then
               progress.empty=0; progress.stalls=progress.stalls+1
               progress.blocked=progress.stalls>=2
              else progress.stalls=0; progress.empty=progress.empty+1; progress.done=progress.empty>=2 end
              s.index=s.index+1; s.phase=nil
              if r.picked==0 then return bot.wait(20) end
             end
             if s.preparing and s.index>#routes then s.preparing=false; s.index=1 end
             if s.index>#routes then s.index=1 end
             local checked=0
             while not s.preparing and (s.routes[s.index].done or s.routes[s.index].blocked) and checked<#routes do s.index=s.index%#routes+1; checked=checked+1 end
             if checked==#routes then
              for i,p in ipairs(s.routes) do if p.blocked then return bot.fail('Round Robin route '..i..' remains unresolved after a revisit; other routes finished; delivered '..s.total..' kits') end end
              local results={}; local byKit={}
              for i,route in ipairs(routes) do
               local p=s.routes[i]; results[i]={name=route.name,kitTypeId=route.kitTypeId,targetStashName=route.targetStashName,delivered=p.delivered,missing=p.missing,recovered=p.recovered,sourceExhausted=true}
               byKit[route.kitTypeId]=(byKit[route.kitTypeId] or 0)+p.delivered
              end
              return bot.done({delivered=s.total,missing=s.missing,recovered=s.recovered,byRoute=results,byKit=byKit,sourceExhausted=true})
             end
             s.phase='planned'; return bot.kit_transfer_plan({index=s.index})
            end
            """;
        String columnRotation="""
            local function columnStep(ctx)
             local s=ctx.state; local ids=ctx.args.kitTypeIds
             if not s.index then
              s.index=1; s.total=0; s.missing=0; s.returned=0; s.types={}
              for i,_ in ipairs(ids) do s.types[i]={column=1,delivered=0} end
             end
             local p=s.types[s.index]
             if s.phase=='planned' then
              local result=ctx.result
              if result and result.sourceUnavailable==true then p.deferred=true; s.index=s.index%#ids+1; s.phase=nil
              else
               local plan=result and result.args
               if not plan or plan.kitTypeId~=ids[s.index] or not plan.targetStash or not plan.returnStash or plan.enderTransport then return bot.fail('Invalid column rotation plan') end
               plan.fillAllocatedColumns=true; plan.targetStash.fillAllocatedColumns=true
               plan.targetStash.allocatedColumns={ctx.args.columnAllocations[ids[s.index]][p.column]}
               s.phase='delivering';return bot.call('task-kit-delivery',plan)
              end
             elseif s.phase=='delivering' then
              local r=ctx.result and ctx.result.value
              if not r or not whole(r.delivered,10000000) or not whole(r.missing or 0,10000000) or not whole(r.returned or 0,10000000) or not (r.sourceExhausted==true or r.sourceDeferred==true or r.destinationFull==true) then return bot.fail('Invalid column rotation receipt') end
              s.total=s.total+r.delivered; s.missing=s.missing+(r.missing or 0); s.returned=s.returned+(r.returned or 0); p.delivered=p.delivered+r.delivered
              if r.destinationFull then
               if r.retained~=0 then return bot.fail('Column rotation must return excess cargo before switching kits') end
               p.column=p.column+1; p.limited=p.column>#ctx.args.columnAllocations[ids[s.index]]
              elseif r.sourceExhausted then p.done=true
              else p.deferred=true end
              s.index=s.index%#ids+1; s.phase=nil
             end
             local checked=0
             while checked<#ids do
              p=s.types[s.index]
              if not p.done and not p.deferred and not p.limited then break end
              s.index=s.index%#ids+1;checked=checked+1
             end
             if checked==#ids then
              local byKit={};local deferred={};local limited={};local exhausted=true
              for i,id in ipairs(ids) do
               p=s.types[i];byKit[id]=p.delivered
               if p.deferred then deferred[id]=true;exhausted=false end
               if p.limited then limited[id]=true;exhausted=false end
              end
              return bot.done({delivered=s.total,missing=s.missing,returned=s.returned,byKit=byKit,deferred=deferred,destinationLimited=limited,sourceExhausted=exhausted})
             end
             s.phase='planned';return bot.kit_transfer_plan({index=s.index})
            end
            """;
        String mixedLoads="""
            local function mixedStep(ctx)
             local s=ctx.state; local ids=ctx.args.kitTypeIds
             if not s.index then s.index=1; s.total=0; s.missing=0; s.loaded=0; s.capacity=ctx.args.count; s.queue={}; s.byKit={}; s.sources={} end
             local function enqueue(target,n)
              if n==0 then return end
              for _,entry in ipairs(s.queue) do
               if entry.kitTypeId==target.kitTypeId then entry.count=entry.count+n; return end
              end
              s.queue[#s.queue+1]={kitTypeId=target.kitTypeId,kitExemplar=target.kitExemplar,incomplete=target.incomplete,includeIncomplete=target.includeIncomplete,count=n}
             end
             if s.phase=='planned' then
              local a=ctx.result and ctx.result.args
              if not a or a.kitTypeId~=ids[s.index] or a.destination~='Stash' or not a.targetStash then return bot.fail('Invalid mixed-load source plan') end
              s.target=s.target or a.targetStash; s.identities=s.identities or {}
              for id,value in pairs(a.kitIdentities or {}) do s.identities[id]=value end
              s.active={kitTypeId=a.kitTypeId,kitExemplar=a.kitExemplar,incomplete=a.incomplete,includeIncomplete=a.includeIncomplete,known=a.knownKitTypeIds or {},size=#a.picks,count=math.min(ctx.args.count,s.capacity)-s.loaded}
              local p=s.sources[a.kitTypeId] or {cursor=0,empty=0}; s.sources[a.kitTypeId]=p
              a.count=s.active.count; a.pickIndex=p.cursor; a.mixedLoad=true
              s.phase='taking'; return bot.stash_resupply(a)
             end
             if s.phase=='taking' then
              local r=ctx.result; local a=s.active; local p=s.sources[a.kitTypeId]
              if not r or not whole(r.picked,a.count) or not whole(r.nextPick,a.size) or r.nextPick<p.cursor or not whole(r.batchCapacity,a.count) or r.batchCapacity<1 or (r.sourceExhausted and (r.nextPick~=a.size or r.sourceDeferred)) or type(r.personalBoxes)~='table' or #r.personalBoxes>36 then return bot.fail('Invalid mixed-load pickup receipt; update the worker before using mixed loads') end
              s.personal=s.personal or r.personalBoxes
              s.capacity=math.min(s.capacity,s.loaded+r.batchCapacity)
              enqueue(a,r.picked); local count=r.picked
              local extras=r.incidental or {}
              if type(extras)~='table' or #extras>36 then return bot.fail('Invalid mixed incidental receipt') end
              for _,extra in ipairs(extras) do
               local known=false; for _,id in ipairs(a.known) do if extra.kitTypeId==id then known=true end end
               if not known or extra.kitTypeId==a.kitTypeId or not whole(extra.count,36) or extra.count<1 then return bot.fail('Invalid mixed incidental kit') end
               enqueue(extra,extra.count); count=count+extra.count
              end
              if count+s.loaded>ctx.args.count or count+s.loaded>36 or (count==0 and not r.sourceExhausted and not r.sourceDeferred) or (r.sourceDeferred and r.nextPick~=a.size) then return bot.fail('Mixed load exceeds budget or has no verified source progress') end
              s.loaded=s.loaded+count
              if r.inventoryFull then s.capacity=s.loaded end
              if r.sourceIssue then s.issue=r.sourceIssue end
              if r.sourceDeferred then
               if type(r.deferredSources)~='table' or #r.deferredSources<1 then return bot.fail('Invalid mixed source deferral') end
               p.deferred=r.deferredSources[1]
              elseif r.sourceExhausted then p.empty=count==0 and p.empty+1 or 0; p.cursor=0
              else p.cursor=r.nextPick end
              s.index=s.index+1; s.phase='collect'
             end
             if s.phase=='storing' then
              local entry=s.queue[s.storeIndex]; local n,missing=depositCount(ctx.result,entry.count)
              s.total=s.total+n; s.missing=s.missing+missing; s.byKit[entry.kitTypeId]=(s.byKit[entry.kitTypeId] or 0)+n
              s.storeIndex=s.storeIndex+1; s.phase='deposit'
             end
             if s.phase=='deposit' then
              if s.storeIndex<=#s.queue then
               local target={}; for k,v in pairs(s.target) do target[k]=v end
               for k,v in pairs(s.queue[s.storeIndex]) do target[k]=v end
               target.kitIdentities=s.identities; target.protectedBoxes=s.personal
               s.phase='storing'; return bot.stash_store(target)
              end
              if s.issue then return bot.fail('Delivered '..s.total..' kits before source needs attention: '..s.issue) end
              s.queue={}; s.loaded=0; s.capacity=ctx.args.count; s.index=1; s.personal=nil; s.phase='collect'
             end
             while s.index<=#ids do
              local id=ids[s.index]; local p=s.sources[id]
              local carried=false; for _,entry in ipairs(s.queue) do if entry.kitTypeId==id then carried=true end end
              if s.loaded>=s.capacity or s.loaded>=ctx.args.count then break end
              if not carried and (not p or (p.empty<2 and not p.deferred)) then s.phase='planned'; return bot.kit_transfer_plan({index=s.index}) end
              s.index=s.index+1
             end
             if s.loaded>0 then s.storeIndex=1; s.phase='deposit'; return bot.wait(0) end
             local pending=false
             for _,id in ipairs(ids) do
              local p=s.sources[id]
              if p and p.deferred then
               if p.retried then return bot.fail('Delivered '..s.total..' kits; mixed source still unresolved: '..p.deferred) end
               p.retried=true; p.deferred=nil; p.cursor=0; p.empty=0
              end
              if not p or p.empty<2 then pending=true end
             end
             if pending then s.index=1; s.phase='collect'; return bot.wait(20) end
             return bot.done({delivered=s.total,missing=s.missing,byKit=s.byKit,sourceExhausted=true})
            end
            """;
        values.put("task-stash-transfer",new Workflow("task-stash-transfer","Transfer Stash Kits","Stash Management",true,List.of(),depositReceipt+roundRobin+columnRotation+mixedLoads+"""
            return function(ctx)
             if ctx.args.columnAllocations then return columnStep(ctx) end
             if ctx.args.mixedLoads then return mixedStep(ctx) end
             if ctx.args.routes then return routeStep(ctx) end
             local s=ctx.state
             if not s.index then s.index=1; s.total=0; s.delivered={}; s.preparing=ctx.args.depositCarried or false end
             s.deferred=s.deferred or {}
             if s.phase=='planned' then
              if not ctx.result or not ctx.result.args then return bot.fail('Missing kit transfer plan') end
              local carried=ctx.result.carried or 0
              if carried<0 or carried>36 or carried~=math.floor(carried) then return bot.fail('Invalid carried-kit count') end
              if s.preparing then
               if carried>0 then
                s.phase='recovering'; s.recoverCount=carried; ctx.result.args.targetStash.count=carried
                return bot.stash_store(ctx.result.args.targetStash)
               end
               s.index=s.index+1
              else
               s.phase='delivering'
               return bot.call('task-kit-delivery',ctx.result.args)
              end
             end
             if s.phase=='recovering' then
              local n,missing=depositCount(ctx.result,s.recoverCount)
              s.total=s.total+n; s.delivered[ctx.args.kitTypeIds[s.index]]=n; s.recovered=(s.recovered or 0)+n; s.missing=(s.missing or 0)+missing; s.index=s.index+1
             end
             if s.phase=='delivering' then
              local r=ctx.result and ctx.result.value
              if not r or (r.sourceExhausted~=true and r.sourceDeferred~=true and r.destinationFull~=true) or (r.sourceDeferred and (r.sourceExhausted or type(r.deferredSources)~='table' or #r.deferredSources==0 or #r.deferredSources>54)) or (r.destinationFull and (not ctx.args.fillAllocatedColumns or r.sourceExhausted or r.sourceDeferred or r.retained~=0 or type(r.returned)~='number' or r.returned<0 or r.returned>10000000 or r.returned~=math.floor(r.returned))) or not r.delivered or r.delivered<0 or r.delivered>10000000 or r.delivered~=math.floor(r.delivered) then return bot.fail('Invalid completed kit transfer receipt') end
              local missing=0; if r.missing~=nil then missing=r.missing end
              if type(missing)~='number' or missing<0 or missing>10000000 or missing~=math.floor(missing) then return bot.fail('Invalid missing-kit receipt') end
              s.missing=(s.missing or 0)+missing
              local kit=ctx.args.kitTypeIds[s.index]
              if r.destinationFull then s.capacityLimited=s.capacityLimited or {};s.capacityLimited[kit]=true;s.returned=(s.returned or 0)+r.returned end
              if r.sourceDeferred then s.deferred[kit]=true; s.lastDeferred=r.deferredSources[1] else s.deferred[kit]=nil end
              s.delivered[ctx.args.kitTypeIds[s.index]]=(s.delivered[ctx.args.kitTypeIds[s.index]] or 0)+r.delivered
              s.total=s.total+r.delivered; s.index=s.index+1
             end
             if s.index>#ctx.args.kitTypeIds then
              if s.preparing and ctx.args.depositOnly then return bot.done({delivered=s.total,missing=s.missing or 0,byKit=s.delivered,recovered=s.recovered or 0,sourceExhausted=false}) end
              if s.preparing then s.preparing=false; s.index=1
              elseif next(s.deferred) then
               if s.retrying then return bot.fail('Transferred '..s.total..' kits; hopper columns remain unresolved (see deferred kit details)') end
               s.retrying=true; s.index=1
              else return bot.done({delivered=s.total,missing=s.missing or 0,byKit=s.delivered,recovered=s.recovered or 0,returned=s.returned or 0,destinationLimited=s.capacityLimited or {},typesCompleted=#ctx.args.kitTypeIds,sourceScanGaps=ctx.args.sourceScanGaps or 0,sourceExhausted=next(s.capacityLimited or {})==nil and (ctx.args.sourceScanGaps or 0)==0}) end
             end
             if s.retrying then
              while s.index<=#ctx.args.kitTypeIds and not s.deferred[ctx.args.kitTypeIds[s.index]] do s.index=s.index+1 end
              if s.index>#ctx.args.kitTypeIds then
               if next(s.deferred) then return bot.fail('Transferred '..s.total..' kits; hopper columns remain unresolved (see deferred kit details)') end
               return bot.done({delivered=s.total,missing=s.missing or 0,byKit=s.delivered,recovered=s.recovered or 0,returned=s.returned or 0,destinationLimited=s.capacityLimited or {},typesCompleted=#ctx.args.kitTypeIds,sourceScanGaps=ctx.args.sourceScanGaps or 0,sourceExhausted=next(s.capacityLimited or {})==nil and (ctx.args.sourceScanGaps or 0)==0})
              end
             end
             s.phase='planned'
             return bot.kit_transfer_plan({index=s.index})
            end
            """,List.of("task-kit-delivery"),List.of("Current")));
        values.put("task-stash-transfer-team",new Workflow("task-stash-transfer-team","Crew Stash Transfer","Stash Management",true,List.of(),"""
            return function(ctx)
             local s=ctx.state
             if not s.phase then s.step=0; s.phase='planning'; return bot.kit_transfer_plan({step=0,receipt={}}) end
             if s.phase=='planning' then
              local r=ctx.result
              if r and r.done then return bot.done({delivered=r.delivered,missing=r.missing or 0,byKit=r.byKit,sourceExhausted=true}) end
              if not r or not r.action then return bot.fail('Missing crew transfer command') end
              local a=r.action; s.phase='working'
              if a.type=='StashVisit' then return bot.stash_visit(a) end
              if a.type=='StashResupply' then return bot.stash_resupply(a) end
              if a.type=='StashDeposit' then return bot.stash_store(a) end
              if a.type=='Tpa' then return bot.tpa(a) end
              if a.type=='Wait' then return bot.wait(a.ticks) end
              return bot.fail('Unknown crew transfer command')
             end
             local r=ctx.result or {}; local receipt={ok=r.ok or false}
             for _,key in ipairs({'picked','nextPick','sourceExhausted','sourceIssue','deposited','missing'}) do receipt[key]=r[key] end
             s.step=s.step+1; s.phase='planning'
             return bot.kit_transfer_plan({step=s.step,receipt=receipt})
            end
            """,List.of(),List.of("Current")));
        values.put("task-recover", new Workflow("task-recover", "Recover supplies", "Common Tasks", true, List.of(),
            "return function(ctx)\n if ctx.state.started then return bot.done() end\n ctx.state.started=true\n return bot.recover(ctx.args)\nend", List.of(), List.of("Current")));
        return values;
    }
    private static void addBuiltin(Map<String, Workflow> values, String id, String name, List<Step> steps) {
        values.put(id, new Workflow(id, name, "Highway Builder", true, steps));
    }
    public List<Workflow> all() { load(); return List.copyOf(entries.values()); }
    public Workflow get(String id) {
        load(); Workflow result = entries.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown workflow: " + id);
        return result;
    }
    public Workflow duplicate(String id, String name, String folder) {
        Workflow source = get(id);
        return source.script().isEmpty() ? save(UUID.randomUUID().toString(), name, folder, source.steps())
            : saveProgram(UUID.randomUUID().toString(), name, folder, source.script(), source.dependencies(), source.profiles());
    }
    public Workflow save(String id, String name, String folder, List<Step> steps) {
        return put(new Workflow(id, name, folder, false, steps));
    }
    public Workflow createProgram(String name, String folder, String script) { return saveProgram(UUID.randomUUID().toString(), name, folder, script, List.of(), List.of("Current")); }
    public Workflow saveProgram(String id, String name, String folder, String script, List<String> dependencies, List<String> profiles) {
        return put(new Workflow(id, name, folder, false, List.of(), script, dependencies, profiles));
    }
    private Workflow put(Workflow input) {
        load(); String id = input.id();
        Workflow previous = entries.get(id);
        if (previous != null && previous.builtin()) throw new IllegalArgumentException("Duplicate a built-in workflow to edit it.");
        UUID.fromString(id);
        if (previous == null && entries.size() >= 256) throw new IllegalArgumentException("Workflow library is full (256 entries).");
        Workflow value = checked(input);
        for (Workflow other : entries.values()) if (!other.id().equals(id) && other.folder().equalsIgnoreCase(value.folder()) && other.name().equalsIgnoreCase(value.name()))
            throw new IllegalArgumentException("That folder already contains a workflow named " + value.name() + ". Choose another name.");
        entries.put(id, value);
        try { validateAll(); persist(); }
        catch (RuntimeException e) { if (previous == null) entries.remove(id); else entries.put(id, previous); throw e; }
        return value;
    }
    public void delete(String id) {
        Workflow value = get(id);
        if (value.builtin()) throw new IllegalArgumentException("Built-in workflows are read-only.");
        for (Workflow other : entries.values()) for (Step step : other.steps())
            if (step.action() == Action.Call && step.target().equals(id)) throw new IllegalStateException("Used by workflow: " + other.name());
        for (Workflow other : entries.values()) if (other.dependencies().contains(id)) throw new IllegalStateException("Used by workflow: " + other.name());
        entries.remove(id);
        try { persist(); } catch (RuntimeException e) { entries.put(id, value); throw e; }
    }
    /** Snapshot the expanded instructions; edits to the library never change a saved or running job. */
    public JsonObject compile(String id) { load(); return compileEntry(id); }
    private JsonObject compileEntry(String id) {
        Workflow value = entries.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown workflow: " + id);
        if (!value.script().isEmpty()) throw new IllegalArgumentException("Choose a native highway workflow here; Lua programs run from the job queue.");
        List<Action> actions = new ArrayList<>(); expand(id, new LinkedHashSet<>(), actions);
        JsonObject plan = new JsonObject(); plan.addProperty("version", 1); plan.addProperty("id", id); plan.addProperty("name", value.name());
        JsonArray encoded = new JsonArray(); actions.forEach(action -> encoded.add(action.name())); plan.add("actions", encoded);
        return checkedPlan(plan);
    }
    private void expand(String id, Set<String> path, List<Action> actions) {
        if (path.size() >= 16 || !path.add(id)) throw new IllegalArgumentException("Workflow call cycle or nesting deeper than 16: " + id);
        Workflow value = entries.get(id);
        if (value == null) throw new IllegalArgumentException("Missing called workflow: " + id);
        if (!value.script().isEmpty()) throw new IllegalArgumentException("A native highway preset cannot call a Lua program. Call the highway preset from Lua instead.");
        for (Step step : value.steps()) {
            if (step.action() == Action.Call) expand(step.target(), path, actions);
            else {
                if (actions.contains(step.action())) throw new IllegalArgumentException("Repeated action " + step.action() + " in " + id);
                actions.add(step.action());
            }
        }
        path.remove(id);
    }
    public static JsonObject checkedPlan(JsonObject input) {
        if (input == null || input.toString().length() > 2048) throw new IllegalArgumentException("Invalid workflow snapshot");
        JsonObject plan = input.deepCopy();
        if (!plan.getAsJsonPrimitive("version").isNumber() || plan.get("version").getAsBigDecimal().intValueExact() != 1)
            throw new IllegalArgumentException("Unsupported workflow version");
        String id = dev.monocle.coordinator.TaskWire.text(plan, "id");
        if (!builtins().containsKey(id)) UUID.fromString(id);
        plan.addProperty("name", checkedName(dev.monocle.coordinator.TaskWire.text(plan, "name")));
        JsonArray values = plan.getAsJsonArray("actions");
        if (values == null || values.isEmpty() || values.size() > 5) throw new IllegalArgumentException("A workflow needs 1–5 distinct native actions");
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        for (JsonElement value : values) {
            Action action = Action.valueOf(value.getAsString());
            if (action == Action.Call || !actions.add(action)) throw new IllegalArgumentException("Workflow snapshot must contain distinct expanded actions");
        }
        boolean excavates = actions.contains(Action.Excavating), paves = actions.contains(Action.Paving);
        plan.addProperty("duty", excavates ? paves ? "Build" : "Excavate" : paves ? "Pave" : "Supply");
        return plan;
    }
    public static String operation(JsonObject plan) {
        return switch (checkedPlan(plan).get("duty").getAsString()) {
            case "Build" -> "Build"; case "Excavate" -> "ClearTunnel"; case "Pave" -> "Pave";
            default -> throw new IllegalArgumentException("A job or worker workflow needs Excavating or Paving; supply-only workflows can be called by another workflow.");
        };
    }
    public static JsonObject legacyPlan(String operation) {
        String id = operation.equals("ClearTunnel") ? "highway-excavate" : Set.of("Pave", "Repair").contains(operation) ? "highway-pave" : DEFAULT_ID;
        return new BotWorkflows(null).compileEntry(id);
    }
    private static Workflow checked(Workflow value) {
        String folder = value.folder().strip();
        if (folder.isEmpty() || folder.length() > 96 || folder.chars().anyMatch(Character::isISOControl) || folder.contains("\\"))
            throw new IllegalArgumentException("Choose a folder path of 1–96 printable characters.");
        for (String part : folder.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals(".."))
            throw new IllegalArgumentException("Workflow folders need nonempty names, without . or ..");
        if (value.script().isEmpty() && value.steps().isEmpty() || value.steps().size() > 32) throw new IllegalArgumentException("A workflow needs native steps or Lua source.");
        if (!value.script().isEmpty()) {
            if (!value.steps().isEmpty()) throw new IllegalArgumentException("A definition is either Lua or a native highway preset.");
            BotLua.validate(value.script());
        }
        if (value.dependencies().size() > 32 || new HashSet<>(value.dependencies()).size() != value.dependencies().size()
            || value.profiles().size() > 16 || value.profiles().stream().anyMatch(name -> name.isBlank() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Invalid workflow dependencies/profile names.");
        for (Step step : value.steps()) {
            if (step.action() == Action.Call && (step.target().isBlank() || step.target().length() > 48)) throw new IllegalArgumentException("Choose a workflow to call.");
            if (step.action() != Action.Call && !step.target().isEmpty()) throw new IllegalArgumentException("Only calls take a workflow target.");
        }
        return new Workflow(value.id(), checkedName(value.name()), folder, value.builtin(), value.steps(), value.script(), value.dependencies(), value.profiles());
    }
    private void validateAll() {
        for (Workflow value : entries.values()) {
            if (value.script().isEmpty()) compileEntry(value.id()); else { BotLua.validate(value.script()); dependencies(value.id(), new LinkedHashSet<>(), new LinkedHashSet<>()); }
        }
    }
    private void dependencies(String id, Set<String> path, Set<String> collected) {
        if (path.size() >= 16 || !path.add(id)) throw new IllegalArgumentException("Workflow dependency cycle or excessive nesting: " + id);
        Workflow value = entries.get(id); if (value == null) throw new IllegalArgumentException("Missing dependency: " + id);
        collected.add(id);
        if (collected.size() > 32) throw new IllegalArgumentException("A job package may contain at most 32 workflows.");
        for (String child : value.dependencies()) dependencies(child, path, collected);
        for (Step step : value.steps()) if (step.action() == Action.Call) dependencies(step.target(), path, collected);
        path.remove(id);
    }
    public JsonObject packageWorkflows(String id) {
        get(id); Set<String> included = new LinkedHashSet<>(); dependencies(id, new LinkedHashSet<>(), included);
        JsonObject packageData = new JsonObject(), programs = new JsonObject(), highways = new JsonObject(); JsonArray profiles = new JsonArray();
        Set<String> profileNames = new LinkedHashSet<>(Set.of("Current"));
        for (String key : included) {
            Workflow value = entries.get(key); profileNames.addAll(value.profiles());
            JsonObject program = new JsonObject(); program.addProperty("name", value.name());
            if (value.script().isEmpty()) {
                JsonObject plan = compileEntry(key); highways.add(key, plan);
                // Native restocking handles its own supplies. Historical recovery is an explicit
                // workflow action, not a prerequisite that can send a new crew to an old jobsite.
                program.addProperty("script", "return function(ctx)\n if ctx.state.started then return bot.done() end\n ctx.state.started=true\n local a=ctx.args\n a.workflow='" + key + "'\n return bot.highway(a)\nend");
            } else program.addProperty("script", value.script());
            programs.add(key, program);
        }
        profileNames.forEach(profiles::add);
        packageData.addProperty("version", 1); packageData.addProperty("entry", id); packageData.add("programs", programs); packageData.add("highways", highways); packageData.add("profileNames", profiles);
        return packageData;
    }
    public JsonObject exportDefinition(String id) { return encode(get(id)); }
    public Workflow importDefinition(JsonObject object) {
        Workflow value = decode(object, UUID.randomUUID().toString());
        return put(value);
    }
    private static Workflow decode(JsonObject object, String id) {
        List<Step> steps = new ArrayList<>();
        if (object.has("steps")) for (JsonElement item : object.getAsJsonArray("steps")) {
            JsonObject step = item.getAsJsonObject(); steps.add(new Step(Action.valueOf(dev.monocle.coordinator.TaskWire.text(step, "action")), dev.monocle.coordinator.TaskWire.text(step, "target")));
        }
        List<String> dependencies = new ArrayList<>(), profiles = new ArrayList<>();
        if (object.has("dependencies")) object.getAsJsonArray("dependencies").forEach(value -> dependencies.add(value.getAsString()));
        if (object.has("profiles")) object.getAsJsonArray("profiles").forEach(value -> profiles.add(value.getAsString()));
        return checked(new Workflow(id, dev.monocle.coordinator.TaskWire.text(object, "name"), dev.monocle.coordinator.TaskWire.text(object, "folder"), false, steps, dev.monocle.coordinator.TaskWire.text(object, "script"), dependencies, profiles));
    }
    private static JsonObject encode(Workflow value) {
        JsonObject object = new JsonObject(); object.addProperty("id", value.id()); object.addProperty("name", value.name()); object.addProperty("folder", value.folder()); object.addProperty("script", value.script());
        JsonArray steps = new JsonArray();
        for (Step step : value.steps()) { JsonObject item = new JsonObject(); item.addProperty("action", step.action().name()); item.addProperty("target", step.target()); steps.add(item); }
        object.add("steps", steps); JsonArray dependencies = new JsonArray(), profiles = new JsonArray(); value.dependencies().forEach(dependencies::add); value.profiles().forEach(profiles::add);
        object.add("dependencies", dependencies); object.add("profiles", profiles); return object;
    }
    private void load() {
        if (failure != null) throw new IllegalStateException(failure);
        if (loaded) return;
        try {
            if (Files.exists(file)) {
                if (Files.size(file) > 1_048_576) throw new IllegalArgumentException("Workflow library is too large");
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.get("version").getAsBigDecimal().intValueExact() != 1) throw new IllegalArgumentException("Unsupported workflow library version");
                JsonArray stored = root.getAsJsonArray("workflows");
                if (stored.size() > 246) throw new IllegalArgumentException("Too many workflows");
                for (JsonElement element : stored) {
                    JsonObject object = element.getAsJsonObject(); String id = dev.monocle.coordinator.TaskWire.text(object, "id"); UUID.fromString(id);
                    Workflow workflow = decode(object, id);
                    if (entries.putIfAbsent(id, workflow) != null) throw new IllegalArgumentException("Duplicate workflow ID");
                }
                validateAll();
            }
            loaded = true;
        } catch (IOException | RuntimeException e) {
            failure = "Cannot read Workers workflows at " + file + ": " + e.getMessage() + ". Original file left untouched.";
            throw new IllegalStateException(failure, e);
        }
    }
    private void persist() {
        JsonObject root = new JsonObject(); root.addProperty("version", 1); JsonArray workflows = new JsonArray();
        for (Workflow value : entries.values()) if (!value.builtin()) {
            workflows.add(encode(value));
        }
        root.add("workflows", workflows); Path temporary = null;
        try {
            Files.createDirectories(file.getParent()); temporary = Files.createTempFile(file.getParent(), "bot-workflows-", ".tmp");
            Files.writeString(temporary, root.toString()); Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException("Could not save Workers workflows: " + e.getMessage(), e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
}
