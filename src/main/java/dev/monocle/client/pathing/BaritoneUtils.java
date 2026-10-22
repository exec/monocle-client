/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.input.Input;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import java.util.LinkedHashMap;
import java.util.Map;

public class BaritoneUtils {
    public static boolean IS_AVAILABLE = false;

    private BaritoneUtils() {
    }
    public static boolean retainsStashGoal(Object expected,Object current,Object recent){return current==expected||current==null&&recent==expected;}
    public static boolean foreignStashController(Object expected,Object controller,boolean active){return controller!=null&&controller!=expected&&active&&!BaritonePathManager.isPauseProcess(controller);}
    public static boolean releasesStashInput(boolean owned,boolean pathing,Class<?> input){
        return owned&&!pathing&&input==baritone.utils.PlayerMovementInput.class;
    }
    public static boolean movementBusy(){
        return PathManagers.get().isPathing()||IS_AVAILABLE&&movementBusy(BaritoneAPI.getProvider().getPrimaryBaritone());
    }
    static boolean movementBusy(baritone.api.IBaritone b){
        var path=b.getPathingBehavior();
        if(path.hasPath()||path.getInProgress().isPresent())return true;
        for(var process:movementProcesses(b))
            if(process.isActive())return true;
        for(var input:java.util.List.of(Input.MOVE_FORWARD,Input.MOVE_BACK,Input.MOVE_LEFT,Input.MOVE_RIGHT,Input.JUMP,Input.SNEAK))
            if(b.getInputOverrideHandler().isInputForcedDown(input))return true;
        return b.getPathingControlManager().mostRecentInControl().filter(p->p.isActive()&&!p.isTemporary()).isPresent();
    }
    private static java.util.List<baritone.api.process.IBaritoneProcess> movementProcesses(baritone.api.IBaritone b){
        return java.util.List.of(b.getCustomGoalProcess(),b.getMineProcess(),b.getFollowProcess(),b.getBuilderProcess(),
            b.getExploreProcess(),b.getFarmProcess(),b.getGetToBlockProcess(),b.getElytraProcess());
    }

    /** A read-only scan lease; cancellation never stops an unrelated replacement goal. */
    public static final class StashNavigation implements AutoCloseable {
        private final Map<baritone.api.Settings.Setting<?>, Object> previous = new LinkedHashMap<>();
        private Goal goal;
        private int requests,idleTicks;
        private final baritone.api.IBaritone baritone;
        private final meteordevelopment.orbit.IEventBus events;
        private boolean closing,closed,subscribed;
        public StashNavigation() {
            this(BaritoneAPI.getProvider().getPrimaryBaritone(),BaritoneAPI.getSettings(),MonocleClient.EVENT_BUS);
        }
        StashNavigation(baritone.api.IBaritone baritone,baritone.api.Settings settings,meteordevelopment.orbit.IEventBus events){
            this.baritone=baritone;this.events=events;
            forbid(settings.allowBreak); forbid(settings.allowPlace); forbid(settings.allowInventory);
            previous.put(settings.allowBreakAnyway, settings.allowBreakAnyway.value);
            settings.allowBreakAnyway.value = java.util.List.of();
        }
        private void forbid(baritone.api.Settings.Setting<Boolean> setting) {
            previous.put(setting, setting.value); setting.value = false;
        }
        public void moveTo(BlockPos target, int mode) {
            if(closing||closed)throw new IllegalStateException("Stash navigation is releasing movement");
            var process=baritone.getCustomGoalProcess();
            var controller=baritone.getPathingControlManager().mostRecentInControl();
            var foreign=controller.filter(p->foreignStashController(process,p,p.isActive()));
            if(foreign.isPresent()||goal!=null&&!retainsStashGoal(goal,process.getGoal(),process.mostRecentGoal()))throw new IllegalStateException("Stash navigation replaced: current="+(process.getGoal()==null?"none":process.getGoal().getClass().getSimpleName())+", previousMatches="+(process.mostRecentGoal()==goal)+", foreignController="+foreign.map(p->p.getClass().getSimpleName()).orElse("none")+"; kits retained");
            // Eating/combat temporarily pause our exact goal. Do not steal control or reset it.
            if(paused()){idleTicks=0;return;}
            var behavior=baritone.getPathingBehavior();
            if(behavior.hasPath()||behavior.getInProgress().isPresent()){idleTicks=0;return;}
            var player=baritone.getPlayerContext().player();
            if(goal!=null&&player!=null&&goal.isInGoal(player.blockPosition()))return;
            // An executing custom goal may have no path; explicitly retry, but never spam unreachable goals.
            if(goal!=null&&(!stashIdleRetry(++idleTicks,requests)))return;
            if(goal==null)goal=mode==0?new GoalBlock(target):mode>0?new GoalNear(target,mode):new GoalGetToBlock(target);
            requests++;idleTicks=0;process.setGoalAndPath(goal);
        }
        public boolean paused(){return baritone.getPathingControlManager().mostRecentInControl().filter(p->BaritonePathManager.isPauseProcess(p)&&p.isActive()).isPresent();}
        public com.google.gson.JsonObject diagnostic(){
            var b=baritone;var behavior=b.getPathingBehavior();
            var result=new com.google.gson.JsonObject();result.addProperty("status",status());result.addProperty("requests",requests);
            result.addProperty("closing",closing);result.addProperty("closed",closed);
            result.addProperty("goal",goal==null?"none":goal.toString());result.addProperty("goalActive",b.getCustomGoalProcess().isActive());
            var player=b.getPlayerContext().player();result.addProperty("atGoal",goal!=null&&player!=null&&goal.isInGoal(player.blockPosition()));
            result.addProperty("controller",b.getPathingControlManager().mostRecentInControl().map(p->p.getClass().getSimpleName()).orElse("none"));
            var forced=new com.google.gson.JsonArray();for(var key:Input.values())
                if(b.getInputOverrideHandler().isInputForcedDown(key))forced.add(key.name());result.add("forcedInputs",forced);
            var executor=behavior.getCurrent();
            if(executor!=null){
                int step=executor.getPosition();var path=executor.getPath();result.addProperty("pathStep",step);result.addProperty("pathLength",path.length());
                if(step>=0&&step<path.movements().size()){
                    var movement=path.movements().get(step);result.addProperty("movement",movement.getClass().getSimpleName());
                    result.addProperty("movementFrom",movement.getSrc().toShortString());result.addProperty("movementTo",movement.getDest().toShortString());
                }
            }
            return result;
        }
        public String status() {
            if(closing&&!closed)return "waiting for safe path cancellation";
            if(baritone.getPathingControlManager().mostRecentInControl().filter(p->BaritonePathManager.isPauseProcess(p)&&p.isActive()).isPresent())return "paused by Monocle";
            var behavior = baritone.getPathingBehavior();
            var executor=behavior.getCurrent();
            if(executor!=null&&executor.getPosition()>=0&&executor.getPosition()<executor.getPath().movements().size()){
                var move=executor.getPath().movements().get(executor.getPosition());
                return "following "+move.getClass().getSimpleName()+" to "+move.getDest().toShortString()
                    +" · jump="+baritone.getInputOverrideHandler().isInputForcedDown(Input.JUMP)+" · requests="+requests;
            }
            return behavior.isPathing() ? "following path" : behavior.getInProgress().isPresent() ? "calculating path" : "no path active · requests="+requests;
        }
        public boolean closing(){return closing;}
        @Override public void close(){release();}
        @EventHandler private void onTick(TickEvent.Post event){release();}
        @SuppressWarnings({"rawtypes", "unchecked"}) public boolean release() {
            if(closed)return true;
            closing=true;
            var process=baritone.getCustomGoalProcess();
            // New processes can be active before mostRecentInControl has caught up this tick.
            boolean foreign=movementProcesses(baritone).stream().skip(1).anyMatch(p->p.isActive())
                ||baritone.getPathingControlManager().mostRecentInControl().filter(p->foreignStashController(process,p,p.isActive())).isPresent();
            if (goal != null && retainsStashGoal(goal,process.getGoal(),process.mostRecentGoal())&&!foreign) {
                var path=baritone.getPathingBehavior();
                boolean cancelled=path.cancelEverything();
                if(!cancelled||path.hasPath()||path.getInProgress().isPresent()){
                    if(!subscribed){events.subscribe(this);subscribed=true;}
                    return false;
                }
                // Baritone clears keys only when a path executor existed; completed goals can retain forced input.
                baritone.getInputOverrideHandler().clearAllKeys();
                var player=baritone.getPlayerContext().player();
                if(player!=null&&player.input!=null&&releasesStashInput(true,false,player.input.getClass()))
                    player.input=new net.minecraft.client.player.KeyboardInput(baritone.getPlayerContext().minecraft().options);
            }
            previous.forEach((setting, value) -> {
                if (Boolean.FALSE.equals(setting.value) || setting.value instanceof java.util.List<?> list && list.isEmpty())
                    ((baritone.api.Settings.Setting) setting).value = value;
            });
            previous.clear(); goal = null;
            closed=true;
            if(subscribed){events.unsubscribe(this);subscribed=false;}
            return true;
        }
    }

    static boolean stashIdleRetry(int idleTicks,int requests){return idleTicks>=20&&requests<3;}

    public static String getPrefix() {
        if (IS_AVAILABLE) {
            return BaritoneAPI.getSettings().prefix.value;
        }

        return "";
    }
}
