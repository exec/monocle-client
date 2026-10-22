package dev.monocle.client.pathing;

import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import dev.monocle.client.events.world.TickEvent;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Proxy;
import java.util.Optional;
import meteordevelopment.orbit.EventBus;
import net.minecraft.core.BlockPos;

public final class StashNavigationTest {
    public static void run() throws Exception {
        State state=new State();
        assert !BaritoneUtils.movementBusy(state.bot);
        state.path=true;state.walking=false;
        assert BaritoneUtils.movementBusy(state.bot):"A paused retained path is not idle";
        state.path=false;state.calculating=true;
        assert BaritoneUtils.movementBusy(state.bot):"A first-segment calculation is not idle";
        state.calculating=false;state.goalActive=true;
        assert BaritoneUtils.movementBusy(state.bot):"An accepted goal owns movement before calculation starts";
        state.goalActive=false;state.mineActive=true;
        assert BaritoneUtils.movementBusy(state.bot):"A pending foreign mine process owns movement too";
        state.mineActive=false;
        state.forced=true;assert BaritoneUtils.movementBusy(state.bot):"Residual forced movement input still owns the player";state.forced=false;

        EventBus events=new EventBus();
        events.registerLambdaFactory("dev.monocle",(method,klass)->MethodHandles.privateLookupIn(klass,MethodHandles.lookup()));
        var constructor=Settings.class.getDeclaredConstructor();constructor.setAccessible(true);
        Settings settings=constructor.newInstance();settings.allowBreak.value=settings.allowPlace.value=settings.allowInventory.value=true;
        var breakExceptions=java.util.List.of(net.minecraft.world.level.block.Blocks.DIRT);settings.allowBreakAnyway.value=breakExceptions;
        var lease=new BaritoneUtils.StashNavigation(state.bot,settings,events);
        lease.moveTo(new BlockPos(0,20,0),-1);
        state.path=true;state.cancellable=false;
        assert !lease.release()&&lease.closing();
        assert !settings.allowBreak.value&&!settings.allowPlace.value&&!settings.allowInventory.value;
        assert settings.allowBreakAnyway.value.isEmpty();
        assert state.keysCleared==0&&BaritoneUtils.movementBusy(state.bot);
        try{lease.moveTo(BlockPos.ZERO,-1);throw new AssertionError("A releasing lease cannot submit another goal");}catch(IllegalStateException expected){}
        events.post(TickEvent.Post.get());
        assert state.cancellations==2&&!settings.allowPlace.value:"Deferred cancellation retains restrictions across ticks";
        state.cancellable=true;state.calculating=true;
        events.post(TickEvent.Post.get());
        assert !state.path&&state.keysCleared==0&&!settings.allowInventory.value:"Cancelled calculations must drain before handoff";
        state.calculating=false;
        events.post(TickEvent.Post.get());
        assert lease.release()&&settings.allowBreak.value&&settings.allowPlace.value&&settings.allowInventory.value;
        assert settings.allowBreakAnyway.value==breakExceptions:"Read-only break exceptions restore only after release";
        assert state.keysCleared==1&&!BaritoneUtils.movementBusy(state.bot);
        int calls=state.cancellations;events.post(TickEvent.Post.get());lease.close();
        assert state.cancellations==calls:"Completed cleanup unsubscribes and is idempotent";

        var replaced=new BaritoneUtils.StashNavigation(state.bot,settings,events);
        replaced.moveTo(BlockPos.ZERO,-1);
        state.goal=new baritone.api.pathing.goals.GoalBlock(8,8,8);state.goalActive=true;
        assert replaced.release()&&state.cancellations==calls&&state.goalActive:"Replacement goals must not be cancelled";
        state.goalActive=false;
        var pendingForeign=new BaritoneUtils.StashNavigation(state.bot,settings,events);
        pendingForeign.moveTo(BlockPos.ZERO,-1);state.mineActive=true;
        assert pendingForeign.release()&&state.mineActive&&state.cancellations==calls:"A new foreign process must survive before it becomes the current controller";
        state.mineActive=state.goalActive=false;
        var neverStarted=new BaritoneUtils.StashNavigation(state.bot,settings,events);
        assert neverStarted.release()&&state.cancellations==calls:"A lease that never started has no path to cancel";
        assert !BaritoneUtils.stashIdleRetry(19,1)&&BaritoneUtils.stashIdleRetry(20,1)&&!BaritoneUtils.stashIdleRetry(200,3);
        int sent=state.goalRequests;var idle=new BaritoneUtils.StashNavigation(state.bot,settings,events);idle.moveTo(BlockPos.ZERO,-1);
        for(int i=0;i<19;i++)idle.moveTo(BlockPos.ZERO,-1);
        assert state.goalRequests==sent+1:"An idle goal is not resubmitted every tick";
        idle.moveTo(BlockPos.ZERO,-1);assert state.goalRequests==sent+2:"A retained goal without a path is explicitly restarted";
        state.calculating=true;for(int i=0;i<30;i++)idle.moveTo(BlockPos.ZERO,-1);
        assert state.goalRequests==sent+2:"A live calculation is never restarted";
        state.calculating=false;state.goal=null;for(int i=0;i<20;i++)idle.moveTo(BlockPos.ZERO,-1);
        assert state.goalRequests==sent+3:"A goal cleared on calculation failure gets a bounded retry";
        for(int i=0;i<100;i++)idle.moveTo(BlockPos.ZERO,-1);
        assert state.goalRequests==sent+3:"Unreachable goals cannot create an endless calculation loop";
        idle.release();
        System.out.println("Stash movement handoff checks passed: paused/calculating ownership, deferred cancellation, asynchronous cleanup, retained settings and foreign-goal preservation.");
    }

    private static final class State {
        boolean path,walking,calculating,goalActive,mineActive,forced,cancellable=true;
        int cancellations,keysCleared,goalRequests;
        Goal goal,recent;
        final Object finder=mock(baritone.api.pathing.calc.IPathFinder.class);
        final IBaritone bot=(IBaritone)Proxy.newProxyInstance(IBaritone.class.getClassLoader(),new Class<?>[]{IBaritone.class},(proxy,method,args)->{
            Class<?> type=method.getReturnType();
            return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->switch(m.getName()){
                case "hasPath" -> path;
                case "isPathing" -> walking;
                case "getInProgress" -> calculating?Optional.of(finder):Optional.empty();
                case "getCurrent" -> null;
                case "cancelEverything" -> {cancellations++;goal=null;goalActive=mineActive=false;if(cancellable)path=false;yield cancellable;}
                case "setGoalAndPath" -> {goalRequests++;goal=recent=(Goal)a[0];goalActive=true;yield null;}
                case "getGoal" -> goal;
                case "mostRecentGoal" -> recent;
                case "isActive" -> method.getName().equals("getCustomGoalProcess")?goalActive:method.getName().equals("getMineProcess")&&mineActive;
                case "mostRecentInControl" -> Optional.empty();
                case "isInputForcedDown" -> forced;
                case "clearAllKeys" -> {keysCleared++;forced=false;yield null;}
                case "player" -> null;
                default -> throw new AssertionError("Unexpected mocked call "+method.getName()+"."+m.getName());
            });
        });
    }
    private static Object mock(Class<?> type){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{throw new AssertionError("Unexpected finder use");});}
}
