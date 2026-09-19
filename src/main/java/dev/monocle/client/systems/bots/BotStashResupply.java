package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.systems.modules.misc.swarm.CrewInventory;
import dev.monocle.client.utils.player.InvUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.*;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Server-confirmed stash-box withdrawal followed by ender-chest deposit. */
final class BotStashResupply {
    private final JsonObject action;private final JsonArray picks,receipt=new JsonArray();private int index,wait,menu=-1,previousSlot=-1,withdrawSent=-1;private BlockPos target;private boolean depositing,finished;
    BotStashResupply(JsonObject action){this.action=action;this.picks=action.getAsJsonArray("picks");}
    boolean done(){return finished&&mc.player.containerMenu==mc.player.inventoryMenu;}
    boolean approaching(){return target!=null&&mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>20.25;}
    BlockPos target(){return target;}
    JsonObject result(){JsonObject r=new JsonObject();r.addProperty("stash",action.get("name").getAsString());r.add("withdrawn",receipt.deepCopy());return r;}
    JsonObject snapshot(){JsonObject s=new JsonObject();s.addProperty("index",index);s.addProperty("depositing",depositing);s.addProperty("finished",finished);s.add("receipt",receipt.deepCopy());return s;}
    void restore(JsonObject s){index=s.get("index").getAsInt();depositing=s.get("depositing").getAsBoolean();finished=s.has("finished")&&s.get("finished").getAsBoolean();receipt.addAll(s.getAsJsonArray("receipt"));if(index<0||index>picks.size()||receipt.size()>index)throw new IllegalArgumentException("Invalid stash resupply checkpoint");}
    String detail(){return depositing?"Filling ender chest from stash supplies":"Collecting stash shulker "+Math.min(index+1,picks.size())+"/"+picks.size();}
    void tick(){
        if(index>=picks.size()&&!depositing){close();depositing=true;target=findEchest();wait=0;if(target==null)throw new IllegalStateException("Put an accessible ender chest within 16 blocks of the mapped stash");}
        if(done())return;
        if(target==null){JsonObject p=picks.get(index).getAsJsonObject();target=new BlockPos(p.get("x").getAsInt(),p.get("y").getAsInt(),p.get("z").getAsInt());}
        if(approaching())return;
        if(mc.player.containerMenu==mc.player.inventoryMenu){if(++wait%20==1)open();return;}
        if(menu<0)menu=mc.player.containerMenu.containerId;if(mc.player.containerMenu.containerId!=menu)return;
        if(depositing)deposit();else withdraw();
    }
    private void open(){
        int safe=-1;for(int i=0;i<9;i++){ItemStack s=mc.player.getInventory().getItem(i);if(s.isEmpty()||s.is(net.minecraft.tags.ItemTags.PICKAXES)){safe=i;break;}}if(safe<0)throw new IllegalStateException("Keep a pickaxe or empty hotbar slot for stash containers");
        if(previousSlot<0)previousSlot=mc.player.getInventory().getSelectedSlot();mc.player.getInventory().setSelectedSlot(safe);
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(target),net.minecraft.core.Direction.UP,target,false));
    }
    private void withdraw(){
        JsonObject pick=picks.get(index).getAsJsonObject();int sourceSlot=pick.get("slot").getAsInt();if(sourceSlot>=mc.player.containerMenu.slots.size())throw new IllegalStateException("Stash container layout changed");
        var slot=mc.player.containerMenu.getSlot(sourceSlot);boolean present=slot.container!=mc.player.getInventory()&&matches(slot.getItem(),pick.get("resource").getAsString());
        if(withdrawSent>=0&&!present){receipt.add(pick.deepCopy());index++;target=null;withdrawSent=-1;close();wait=0;return;}
        if(!present)throw new IllegalStateException("Selected stash shulker is no longer in its scanned slot");
        if(withdrawSent<0||mc.player.tickCount-withdrawSent>=20){InvUtils.shiftClick().slotId(sourceSlot);withdrawSent=mc.player.tickCount;}
    }
    private void deposit(){
        int empty=-1;for(int i=0;i<mc.player.containerMenu.slots.size();i++){var s=mc.player.containerMenu.getSlot(i);if(s.container==mc.player.getInventory())break;if(!s.hasItem()){empty=i;break;}}
        int inventory=-1;for(int i=0;i<36;i++)if(matchesAny(mc.player.getInventory().getItem(i))){inventory=i;break;}
        if(inventory<0||empty<0){finished=true;close();return;}InvUtils.move().from(inventory).toId(empty);wait=0;
    }
    private boolean matchesAny(ItemStack stack){for(JsonElement e:picks)if(matches(stack,e.getAsJsonObject().get("resource").getAsString()))return true;return false;}
    private static boolean matches(ItemStack stack,String resource){if(!(stack.getItem() instanceof BlockItem b)||!(b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock))return false;return BotStashScan.classify(List.of(stack)).asList().stream().map(JsonElement::getAsJsonObject).anyMatch(box->resource.equals(box.get("dominant").getAsString())&&!box.get("mixed").getAsBoolean());}
    private BlockPos findEchest(){return BlockPos.betweenClosedStream(mc.player.blockPosition().offset(-16,-4,-16),mc.player.blockPosition().offset(16,4,16)).filter(p->mc.level.getBlockEntity(p) instanceof EnderChestBlockEntity).min(Comparator.comparingDouble(p->Vec3.atCenterOf(p).distanceToSqr(mc.player.position()))).map(BlockPos::immutable).orElse(null);}
    void close(){if(mc.player!=null&&mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();menu=-1;if(previousSlot>=0){mc.player.getInventory().setSelectedSlot(previousSlot);previousSlot=-1;}}
}
