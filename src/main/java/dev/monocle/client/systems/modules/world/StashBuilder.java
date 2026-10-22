package dev.monocle.client.systems.modules.world;

import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.pathing.PathManagers;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.systems.modules.*;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.movement.elytrafly.*;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.world.*;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import java.util.*;

/** Additive construction only. Existing inventory, terrain and incorrectly oriented containers are never removed. */
public final class StashBuilder extends Module {
    public enum Facing { North, East, South, West;
        Direction direction(){return Direction.valueOf(name().toUpperCase(Locale.ROOT));}
    }
    private final SettingGroup general=settings.getDefaultGroup();
    private final Setting<BlockPos> origin=general.add(new BlockPosSetting.Builder().name("origin").description("First left chest of the bottom row. Set explicitly, or use the origin button below.").defaultValue(BlockPos.ZERO).build());
    private final Setting<Facing> facing=general.add(new EnumSetting.Builder<Facing>().name("chest-facing").description("Direction the chest fronts face; higher rows step backward.").defaultValue(Facing.North).build());
    private final Setting<Integer> columns=general.add(new IntSetting.Builder().name("columns").description("Double chests across each row. Main uses 128.").defaultValue(8).range(1,512).sliderRange(1,128).build());
    private final Setting<Integer> rows=general.add(new IntSetting.Builder().name("rows").description("Rows upward and backward. Main uses 19.").defaultValue(8).range(1,128).sliderRange(1,32).build());
    private final Setting<Integer> perTick=general.add(new IntSetting.Builder().name("placements-per-tick").description("Maximum attempts per tick; confirmed progress may be slower.").defaultValue(2).range(1,2).sliderRange(1,2).build());
    private final Setting<Boolean> autopilot=general.add(new BoolSetting.Builder().name("autopilot").description("Use existing local flight routing and configured Vanilla ElytraFly. Otherwise move within reach yourself.").defaultValue(false).build());
    private record Pending(StashLayout.Placement placement,int index,int sequence,int tick) {}
    private List<StashLayout.Placement> plan=List.of();
    private final Map<BlockPos,Pending> pending=new LinkedHashMap<>();
    private ClientLevel world;
    private Direction direction;
    private int cursor,verified,failures,audit,launch=-1,lastProgress,rotationTick=-1;
    private boolean queued,sending,flightOwned;
    private long epoch;
    private StashLayout.Placement sendingPlacement;
    private List<Vec3> route=List.of();
    private BlockPos routeTarget;
    private int routeTick;
    private String status="Set the origin, dimensions and chest facing before enabling.";
    public StashBuilder(){super(Categories.World,"stash-builder","Build Main-style diagonal double-chest/hopper banks without a schematic or external printer.");}
    public boolean controlsInventory(){return isActive();}
    public boolean controlsPlayer(){return isActive()&&autopilot.get();}
    @Override public WWidget getWidget(GuiTheme theme){
        WVerticalList list=theme.verticalList();
        list.add(theme.label("Origin is the first left chest, not your standing block. Resume by enabling with the same origin and settings; existing correct blocks are reused.",360));
        list.add(theme.button("Set origin 3 blocks ahead / face toward me")).widget().action=()->{
            if(isActive()){warning("Stop the builder before changing its origin.");return;}
            if(mc.player==null)return;Direction back=mc.player.getDirection();origin.set(mc.player.blockPosition().relative(back,3));facing.set(Facing.valueOf(back.getOpposite().name().substring(0,1)+back.getOpposite().name().substring(1).toLowerCase(Locale.ROOT)));
            info("Origin %s. Chest fronts face %s.",origin.get().toShortString(),facing.get());
        };
        list.add(theme.label("Main replica: 128 columns × 19 rows = 4,864 chests + 4,608 hoppers. This prototype uses carried inventory; it never mines or replaces containers.",360));
        return list;
    }
    @Override public void onActivate(){
        epoch++;
        if(mc.player==null||mc.level==null||mc.gameMode==null){disable();return;}
        if(conflict()){stop("Stop other worker, highway, printer, stash-scan and pathing controllers first");return;}
        try{plan=StashLayout.generate(origin.get(),facing.get().direction(),columns.get(),rows.get());}
        catch(IllegalArgumentException e){stop(e.getMessage());return;}
        world=mc.level;direction=facing.get().direction();
        for(var p:plan)if(!world.isInWorldBounds(p.pos())||!world.getWorldBorder().isWithinBounds(p.pos())){stop("Build extends outside world bounds");return;}
        if(autopilot.get()&&!usableFlight()){stop("Autopilot requires a healthy equipped elytra and Vanilla ElytraFly");return;}
        cursor=verified=failures=audit=0;pending.clear();route=List.of();routeTarget=null;launch=-1;queued=false;lastProgress=mc.player.tickCount;
        status="Building "+plan.size()+" blocks; existing correct storage is reused";
    }
    private boolean conflict(){return Bots.get().tasks().hasWork()||Modules.get().get(HighwayBuilder.class).isActive()
        ||Modules.get().get(PrinterHelper.class).isActive()||Modules.get().get(AutoWither.class).isActive()
        ||Modules.get().get(StashManager.class).isScanning()||PathManagers.get().isPathing();}
    @Override public void onDeactivate(){epoch++;hold();if(flightOwned&&Modules.get().get(ElytraFly.class)!=null)fly().clearAutopilot();flightOwned=false;pending.clear();queued=false;world=null;route=List.of();}
    @Override public String getInfoString(){return status;}
    private void stop(String reason){status=reason;if(mc.player!=null)warning("%s. Existing blocks and supplies are left intact.",reason);if(isActive())disable();}
    @EventHandler private void tick(TickEvent.Pre event){
        if(mc.player==null||mc.level!=world||!mc.player.isAlive()){stop("World/player changed; re-enable at the saved origin to recheck");return;}
        if(conflict()){stop("Another controller acquired movement");return;}
        if(mc.player.getHealth()+mc.player.getAbsorptionAmount()<12){stop("Low health");return;}
        if(autopilot.get()&&!usableFlight()){stop("Elytra needs repair or flight configuration changed");return;}
        hold();
        if(mc.gui.screen()!=null||mc.player.isUsingItem()||!mc.player.containerMenu.getCarried().isEmpty())return;
        if(!pending.isEmpty()){
            if(mc.player.tickCount-pending.values().iterator().next().tick()<10)return;
            if(++failures>=4){stop("Placement confirmation timed out; inspect "+pending.keySet().iterator().next().toShortString());return;}
            cursor=pending.values().stream().mapToInt(Pending::index).min().orElse(cursor);pending.clear();return;
        }
        int checked=0;
        while(cursor<plan.size()&&checked++<64){var p=plan.get(cursor);if(!world.hasChunkAt(p.pos())){status="Load the next stash chunk at "+p.pos().toShortString();return;}
            if(predicted(p.pos()))return;
            BlockState actual=world.getBlockState(p.pos());if(matches(p,actual,true)){cursor++;lastProgress=mc.player.tickCount;continue;}
            if(!actual.canBeReplaced()){stop("Wrong block, facing or chest pairing at "+p.pos().toShortString()+"; no automatic removal");return;}break;
        }
        if(cursor==plan.size()){
            for(int n=0;n<128&&audit<plan.size();n++,audit++){var p=plan.get(audit);if(!world.hasChunkAt(p.pos())){status="Load completed rows for final verification";return;}
                if(predicted(p.pos()))return;
                if(!matches(p,world.getBlockState(p.pos()),false)){stop("Final shape verification needs attention at "+p.pos().toShortString());return;}}
            if(audit<plan.size()){status="Final verification · "+audit+"/"+plan.size();return;}
            status="Complete · "+plan.size()+" verified blocks";info("%s",status);disable();return;
        }
        var p=plan.get(cursor);
        if(!reachable(p.pos())){if(autopilot.get())navigate(p.pos());else status="Move within reach of "+p.pos().toShortString();return;}
        if(queued||rotationTick==mc.player.tickCount)return;
        queued=true;rotationTick=mc.player.tickCount;
        long token=epoch;
        Rotations.rotate(direction.getOpposite().toYRot(),0,100,()->{if(epoch!=token)return;queued=false;if(isActive()&&mc.level==world)placeBatch();});
    }
    static boolean matches(StashLayout.Placement p,BlockState actual,boolean building){
        if(p.kind()==StashLayout.Kind.Hopper)return actual.is(Blocks.HOPPER)&&actual.getValue(HopperBlock.FACING)==p.facing();
        return actual.is(Blocks.CHEST)&&actual.getValue(ChestBlock.FACING)==p.facing()
            &&(actual.getValue(ChestBlock.TYPE)==p.state().getValue(ChestBlock.TYPE)||building&&p.kind()==StashLayout.Kind.LeftChest&&actual.getValue(ChestBlock.TYPE)==ChestType.SINGLE);
    }
    private void placeBatch(){
        if(mc.gui.screen()!=null||mc.player.isUsingItem()||conflict())return;
        Input oldInput=mc.player.input.keyPresses;boolean oldShift=mc.player.isShiftKeyDown();
        Input sneaking=new Input(oldInput.forward(),oldInput.backward(),oldInput.left(),oldInput.right(),oldInput.jump(),true,oldInput.sprint());
        mc.player.input.keyPresses=sneaking;mc.player.setShiftKeyDown(true);mc.getConnection().send(new ServerboundPlayerInputPacket(sneaking));
        try{for(int n=0;n<perTick.get()&&cursor<plan.size();n++){
            var p=plan.get(cursor);if(!world.hasChunkAt(p.pos())||!reachable(p.pos())||!world.getBlockState(p.pos()).canBeReplaced())break;
            if(new AABB(p.pos()).intersects(mc.player.getBoundingBox())){status="Move out of the next placement block";break;}
            var item=p.kind()==StashLayout.Kind.Hopper?Items.HOPPER:Items.CHEST;var found=InvUtils.findInHotbar(item);
            if(!found.found()){
                var source=InvUtils.find(item);if(source.found()){InvUtils.move().from(source.slot()).toHotbar(mc.player.getInventory().getSelectedSlot());status="Moving construction supplies to hotbar";}else status="Supply "+item+"; progress retained at "+p.pos().toShortString();break;
            }
            BlockHitResult hit=placementHit(p);if(hit==null){stop("No safe attachment for "+p.pos().toShortString());break;}
            if(!BlockUtils.canPlaceBlock(p.pos(),true,p.state().getBlock())){status="Placement space is occupied";break;}
            int slot=found.slot();boolean offhand=found.isOffhand();sendingPlacement=p;sending=true;if(!offhand)InvUtils.swap(slot,true);
            try{mc.gameMode.useItemOn(mc.player,offhand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND,hit);}finally{if(!offhand)InvUtils.swapBack();sending=false;}
            if(!pending.containsKey(p.pos())){status="Placement was not sent; retrying";break;}
            cursor++;status="Building · "+verified+" confirmed · "+cursor+"/"+plan.size()+" planned";
        }}finally{mc.player.input.keyPresses=oldInput;mc.player.setShiftKeyDown(oldShift);mc.getConnection().send(new ServerboundPlayerInputPacket(oldInput));}
    }
    private BlockHitResult placementHit(StashLayout.Placement p){
        BlockPos clicked=p.pos();Direction face=Direction.UP;
        if(p.kind()==StashLayout.Kind.Hopper){clicked=p.pos().relative(direction);face=direction.getOpposite();if(!world.getBlockState(clicked).is(Blocks.CHEST))return null;}
        else if(world.hasChunkAt(p.partner())&&world.getBlockState(p.partner()).is(Blocks.CHEST)
            &&world.getBlockState(p.partner()).getValue(ChestBlock.FACING)==direction&&world.getBlockState(p.partner()).getValue(ChestBlock.TYPE)==ChestType.SINGLE){clicked=p.partner();face=p.kind()==StashLayout.Kind.LeftChest?direction.getCounterClockWise():direction.getClockWise();}
        else if(!world.getBlockState(p.pos().below()).canBeReplaced())clicked=p.pos().below();
        return new BlockHitResult(Vec3.atCenterOf(clicked).add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5),face,clicked,false);
    }
    private boolean predicted(BlockPos p){return world.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(p.asLong());}
    private boolean reachable(BlockPos p){return p.distToCenterSqr(mc.player.getEyePosition())<=4.25*4.25;}
    @EventHandler private void sent(PacketEvent.Sent event){if(sending&&event.packet instanceof ServerboundUseItemOnPacket packet)pending.put(sendingPlacement.pos(),new Pending(sendingPlacement,cursor,packet.getSequence(),mc.player.tickCount));}
    public void onServerBlockAck(int sequence){if(!isActive()||world!=mc.level)return;confirm(sequence);}
    public void onServerBlockUpdate(BlockPos pos,BlockState state){if(isActive()&&world==mc.level){Pending p=pending.get(pos);if(p!=null&&matches(p.placement(),state,true)){pending.remove(pos);verified++;failures=0;lastProgress=mc.player.tickCount;}}}
    private void confirm(int sequence){for(var it=pending.entrySet().iterator();it.hasNext();){var p=it.next().getValue();if(p.sequence()<=sequence&&!predicted(p.placement().pos())&&matches(p.placement(),world.getBlockState(p.placement().pos()),true)){it.remove();verified++;failures=0;lastProgress=mc.player.tickCount;}}}
    private ElytraFly fly(){return Modules.get().get(ElytraFly.class);}
    private boolean usableFlight(){var e=mc.player.getItemBySlot(EquipmentSlot.CHEST);return fly().flightMode.get()==ElytraFlightModes.Vanilla&&e.has(DataComponents.GLIDER)&&(!e.isDamageableItem()||e.getMaxDamage()-e.getDamageValue()>20);}
    private void hold(){if(flightOwned&&mc.player!=null&&fly().isActive())fly().requestAutopilot(Vec3.ZERO);}
    private boolean clear(AABB box){
        if(!PrinterFlight.loaded(box,world.getChunkSource()::hasChunk)||box.minY<world.getMinY()||box.maxY>world.getMaxY()+1||world.getBlockCollisions(mc.player,box).iterator().hasNext())return false;
        for(BlockPos p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),BlockPos.containing(Math.nextDown(box.maxX),Math.nextDown(box.maxY),Math.nextDown(box.maxZ)))){var b=world.getBlockState(p);if(!world.getWorldBorder().isWithinBounds(p)||!b.getFluidState().isEmpty()||b.is(Blocks.END_PORTAL)||b.is(Blocks.END_GATEWAY)||b.is(Blocks.FIRE)||b.is(Blocks.SOUL_FIRE)||b.is(Blocks.COBWEB))return false;}
        return true;
    }
    private void navigate(BlockPos target){
        if(mc.player.tickCount-lastProgress>200){stop("No construction/movement progress; move nearer the next target and re-enable");return;}
        if(!fly().isActive())fly().enable();
        flightOwned=true;fly().requestAutopilot(Vec3.ZERO);
        if(!mc.player.isFallFlying()){
            if(mc.player.onGround()){if(!PrinterFlight.segmentClear(mc.player.position(),mc.player.position().add(0,1.5,0),mc.player.getBbWidth()+.12,1.8,this::clear)){stop("No safe takeoff clearance");return;}mc.player.jumpFromGround();launch=mc.player.tickCount;}
            else if(launch<0)launch=mc.player.tickCount;
            else if((mc.player.tickCount-launch)%4==3)mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player,ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            status="Taking off for next construction block";return;
        }
        if(!target.equals(routeTarget)||mc.player.tickCount-routeTick>=20){routeTarget=target;routeTick=mc.player.tickCount;route=PrinterFlight.routeToPlacement(mc.player.position(),target,mc.player.getBbWidth()+.12,mc.player.getBbHeight(),mc.player.getEyeHeight(),4,this::clear);}
        if(route.isEmpty()){status="No local construction flight route; reposition or disable autopilot";return;}
        Vec3 next=route.getLast();for(Vec3 point:route)if(point.distanceToSqr(mc.player.position())>.16){next=point;break;}
        double speed=Math.min(.25,fly().horizontalSpeed.get());
        Vec3 movement=PrinterFlight.safeVelocity(mc.player.position(),next,speed,mc.player.getBbWidth()+.12,mc.player.getBbHeight(),this::clear);
        if(!fly().requestConfiguredAutopilot(movement))stop("ElytraFly rejected construction movement");else status="Flying to next construction block";
    }
}
