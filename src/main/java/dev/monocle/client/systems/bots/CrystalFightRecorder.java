package dev.monocle.client.systems.bots;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.entity.EntityAddedEvent;
import dev.monocle.client.events.entity.EntityRemovedEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.BlockUpdateEvent;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.CrystalAura;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.monocle.client.MonocleClient.mc;

/** Client-observed Crystal Guard evidence, kept separate from the Minecraft log. */
public final class CrystalFightRecorder implements AutoCloseable {
    private static volatile CrystalFightRecorder active;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final FightEventStore store;
    private final UUID subject;
    private final Set<String> targetNames;
    private final Map<UUID, Float> health = new HashMap<>();
    private final Map<UUID, Integer> hurt = new HashMap<>();
    private final Map<UUID, Damage> damage = new HashMap<>();
    private final Map<BlockPos, Long> ownCrystalBases = new HashMap<>();
    private final Map<BlockPos, Intent> ownBlockIntents = new HashMap<>();
    private final Map<Integer, Long> knownCrystals = new HashMap<>();
    private final Map<Integer, Long> ownCrystals = new HashMap<>();
    private final Map<UUID, Long> attacked = new HashMap<>();
    private final Map<String, String> armor = new HashMap<>();
    private Map<String, Integer> foods = Map.of();
    private final Map<String, Boolean> modules = new HashMap<>();
    private boolean flying, using, sampled, closed;
    private String usedItem = "";
    private int tick;

    private record Damage(long at, String cause, int directId, boolean crystal, boolean ownCrystal) { }
    private record Intent(long at, String role) { }

    private CrystalFightRecorder(FightEventStore store, UUID subject, Set<String> targetNames) {
        this.store = store; this.subject = subject; this.targetNames = targetNames;
    }

    static CrystalFightRecorder start(UUID job, String server, String dimension, JsonObject args) throws Exception {
        if (active != null) throw new IllegalStateException("Another Crystal Guard recording is active");
        UUID subject = UUID.fromString(args.get("target").getAsString());
        Set<String> names = new HashSet<>();
        if (args.has("combatTargets")) args.getAsJsonArray("combatTargets").forEach(value -> names.add(value.getAsString().toLowerCase(java.util.Locale.ROOT)));
        String filename = "crystal-" + FILE_TIME.format(Instant.now()) + "-" + mc.getUser().getName() + "-" + job + ".sqlite";
        Path path = MonocleClient.FOLDER.toPath().resolve("combat-logs").resolve(filename);
        FightEventStore store = new FightEventStore(path, Map.of(
            "schema", "crystal-fight-1", "job", job.toString(), "worker", mc.player.getUUID().toString(),
            "worker_name", mc.player.getName().getString(), "subject", subject.toString(),
            "targets", names.isEmpty() ? "all_nonfriends" : String.join(",", names),
            "server", server, "dimension", dimension, "started_at", Instant.now().toString()));
        CrystalFightRecorder recorder = new CrystalFightRecorder(store, subject, Set.copyOf(names));
        active = recorder;
        MonocleClient.EVENT_BUS.subscribe(recorder);
        recorder.event("session_start", "observed", mc.player, new JsonObject());
        return recorder;
    }

    Path path() { return store.path(); }

    public static void auraPlan(BlockPos base, LivingEntity target, double predictedTarget, double predictedSelf,
                         boolean support, List<BlockPos> cover) {
        CrystalFightRecorder recorder = active;
        if (recorder == null) return;
        JsonObject data = new JsonObject();
        data.addProperty("base", base.asLong()); data.addProperty("predictedTargetDamage", predictedTarget);
        data.addProperty("predictedSelfDamage", predictedSelf); data.addProperty("needsBase", support);
        JsonArray blocks = new JsonArray(); cover.forEach(pos -> blocks.add(pos.asLong())); data.add("cover", blocks);
        recorder.event("aura_plan", "predicted", target, data);
    }

    public static void blockAttempt(BlockPos pos, String role) {
        CrystalFightRecorder recorder = active;
        if (recorder == null) return;
        recorder.ownBlockIntents.put(pos.immutable(), new Intent(System.currentTimeMillis(), role));
        JsonObject data = new JsonObject(); data.addProperty("role", role); data.addProperty("block", "minecraft:obsidian");
        recorder.event("block_place_attempt", "intent", mc.player, Vec3.atCenterOf(pos), data);
    }

    public static void crystalAttempt(BlockPos base, double predictedDamage) {
        CrystalFightRecorder recorder = active;
        if (recorder == null) return;
        recorder.ownCrystalBases.put(base.immutable(), System.currentTimeMillis());
        JsonObject data = new JsonObject(); data.addProperty("base", base.asLong()); data.addProperty("predictedTargetDamage", predictedDamage);
        recorder.event("crystal_place_attempt", "intent", mc.player, Vec3.atCenterOf(base.above()), data);
    }

    public static void crystalAttack(Entity crystal, LivingEntity target, double predictedDamage) {
        CrystalFightRecorder recorder = active;
        if (recorder == null) return;
        JsonObject data = new JsonObject(); data.addProperty("crystalEntityId", crystal.getId());
        if (target != null) {
            data.addProperty("target", target.getUUID().toString()); data.addProperty("predictedTargetDamage", predictedDamage);
            recorder.attacked.put(target.getUUID(), System.currentTimeMillis());
        }
        recorder.event("crystal_attack_attempt", "intent", mc.player, crystal.position(), data);
    }

    public static void decision(String state, Player opponent, Vec3 goal) {
        CrystalFightRecorder recorder = active;
        if (recorder == null) return;
        JsonObject data = new JsonObject(); data.addProperty("state", state);
        if (opponent != null) data.addProperty("opponent", opponent.getUUID().toString());
        if (goal != null) { data.addProperty("goalX", goal.x); data.addProperty("goalY", goal.y); data.addProperty("goalZ", goal.z); }
        recorder.event("guard_decision", "intent", mc.player, data);
    }

    void sample() {
        store.check();
        if (closed || mc.level == null || mc.player == null) return;
        tick = mc.player.tickCount;
        long cutoff = System.currentTimeMillis() - 5_000;
        ownBlockIntents.values().removeIf(intent -> intent.at() < cutoff);
        ownCrystalBases.values().removeIf(at -> at < cutoff);
        knownCrystals.values().removeIf(at -> at < cutoff);
        ownCrystals.values().removeIf(at -> at < cutoff);
        samplePlayer(mc.player, "worker");
        for (Player player : mc.level.players()) if (player != mc.player && relevant(player)) samplePlayer(player, player.getUUID().equals(subject) ? "subject" : "opponent");
        sampleFood();
        sampleModules();
        sampled = true;
    }

    private boolean relevant(Player player) {
        if (player.getUUID().equals(subject)) return true;
        if (!targetNames.isEmpty()) return targetNames.contains(player.getName().getString().toLowerCase(java.util.Locale.ROOT));
        if (!Friends.get().shouldAttack(player)) return false;
        Player leader = mc.level.getPlayerByUUID(subject);
        return leader != null && player.distanceToSqr(leader) <= 64 * 64;
    }

    private void samplePlayer(Player player, String role) {
        JsonObject data = new JsonObject(); data.addProperty("role", role);
        data.addProperty("yaw", player.getYRot()); data.addProperty("pitch", player.getXRot());
        data.addProperty("vx", player.getDeltaMovement().x); data.addProperty("vy", player.getDeltaMovement().y); data.addProperty("vz", player.getDeltaMovement().z);
        data.addProperty("health", player.getHealth()); data.addProperty("absorption", player.getAbsorptionAmount());
        data.addProperty("fallFlying", player.isFallFlying()); data.addProperty("hurtTime", player.hurtTime);
        data.addProperty("alive", player.isAlive());
        data.addProperty("mainHand", item(player.getMainHandItem())); data.addProperty("offhand", item(player.getOffhandItem()));
        if (player == mc.player) {
            data.addProperty("hunger", player.getFoodData().getFoodLevel());
            data.addProperty("saturation", player.getFoodData().getSaturationLevel());
        }
        event("motion", "observed", player, data);
        sampleEquipment(player);
        float total = player.getHealth() + player.getAbsorptionAmount();
        Float previous = health.put(player.getUUID(), total);
        if (previous != null && Math.abs(previous - total) > .001f) {
            JsonObject change = new JsonObject(); change.addProperty("before", previous); change.addProperty("after", total);
            change.addProperty("delta", total - previous); change.addProperty("health", player.getHealth()); change.addProperty("absorption", player.getAbsorptionAmount());
            Damage source = damage.get(player.getUUID());
            if (total < previous && source != null && System.currentTimeMillis() - source.at() < 500) {
                change.addProperty("recentDamageCause", source.cause()); change.addProperty("directEntityId", source.directId());
                change.addProperty("ourCrystal", source.ownCrystal());
                if (source.crystal())
                    event(source.ownCrystal() ? "our_crystal_damage" : "unattributed_crystal_damage", "packet_correlated", player, change.deepCopy());
            }
            event("health_change", "observed", player, change);
        }
        int currentHurt = player.hurtTime;
        if (currentHurt > hurt.getOrDefault(player.getUUID(), 0)) event("hurt_animation", "observed", player, new JsonObject());
        hurt.put(player.getUUID(), currentHurt);
    }

    private void sampleEquipment(Player player) {
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            ItemStack stack = player.getItemBySlot(slot);
            String state = item(stack) + ":" + stack.getCount() + ":" + stack.getDamageValue() + ":" + stack.getMaxDamage();
            String previous = armor.put(player.getUUID() + ":" + slot.getName(), state);
            if (previous == null || !previous.equals(state)) {
                JsonObject data = new JsonObject(); data.addProperty("slot", slot.getName()); data.addProperty("before", previous); data.addProperty("after", state);
                event(previous == null ? "equipment_state" : "equipment_change", "observed", player, data);
            }
        }
    }

    private void sampleFood() {
        Map<String, Integer> current = new HashMap<>();
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) if (stack.has(DataComponents.FOOD)) current.merge(item(stack), stack.getCount(), Integer::sum);
        ItemStack offhand = mc.player.getOffhandItem(); if (offhand.has(DataComponents.FOOD)) current.merge(item(offhand), offhand.getCount(), Integer::sum);
        for (var entry : foods.entrySet()) if (current.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
            JsonObject data = new JsonObject(); data.addProperty("item", entry.getKey()); data.addProperty("before", entry.getValue());
            data.addProperty("after", current.getOrDefault(entry.getKey(), 0));
            event("food_count_decrease", "observed", mc.player, data);
            if (using) event("food_eaten", "inferred", mc.player, data.deepCopy());
        }
        if (!sampled) { JsonObject initial = new JsonObject(); current.forEach(initial::addProperty); event("food_inventory", "observed", mc.player, initial); }
        foods = current;
        boolean nowUsing = mc.player.isUsingItem() && mc.player.getUseItem().has(DataComponents.FOOD);
        String nowItem = nowUsing ? item(mc.player.getUseItem()) : "";
        if (nowUsing != using || nowUsing && !nowItem.equals(usedItem)) {
            JsonObject data = new JsonObject(); data.addProperty("item", nowUsing ? nowItem : usedItem);
            event(nowUsing ? "food_use_start" : "food_use_end", "observed", mc.player, data);
        }
        using = nowUsing; usedItem = nowItem;
    }

    private void sampleModules() {
        boolean nowFlying = mc.player.isFallFlying();
        if (!sampled || nowFlying != flying) {
            JsonObject data = new JsonObject(); data.addProperty("flying", nowFlying);
            event(!sampled ? "elytra_flight_state" : nowFlying ? "elytra_flight_start" : "elytra_flight_end", "observed", mc.player, data); flying = nowFlying;
        }
        for (String name : List.of("elytra-fly", "crystal-aura", "auto-totem", "auto-gap", "auto-eat", "auto-armor")) {
            var module = Modules.get().get(name);
            boolean enabled = module != null && module.isActive();
            Boolean before = modules.put(name, enabled);
            if (before == null || before != enabled) {
                JsonObject data = new JsonObject(); data.addProperty("module", name); data.addProperty("active", enabled);
                event(before == null ? "module_state" : "module_toggle", "observed", mc.player, data);
            }
        }
    }

    private static String item(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @EventHandler private void entityAdded(EntityAddedEvent event) {
        if (event.entity instanceof EndCrystal crystal && nearFight(crystal.position())) {
            BlockPos base = crystal.blockPosition().below();
            Long attempt = ownCrystalBases.get(base);
            boolean ours = attempt != null && System.currentTimeMillis() - attempt < 1_500;
            knownCrystals.put(crystal.getId(), System.currentTimeMillis());
            if (ours) ownCrystals.put(crystal.getId(), System.currentTimeMillis());
            JsonObject data = new JsonObject(); data.addProperty("entityId", crystal.getId()); data.addProperty("base", base.asLong());
            data.addProperty("ourPlacementCorrelated", ours);
            event("crystal_spawn", "observed", crystal, data);
        }
    }

    @EventHandler private void entityRemoved(EntityRemovedEvent event) {
        if (event.entity instanceof EndCrystal crystal && nearFight(crystal.position())) {
            JsonObject data = new JsonObject(); data.addProperty("entityId", crystal.getId()); data.addProperty("ourPlacementCorrelated", ownCrystals.containsKey(crystal.getId()));
            event("crystal_removed", "observed", crystal, data);
        } else if (event.entity instanceof Player player) {
            health.remove(player.getUUID()); hurt.remove(player.getUUID()); damage.remove(player.getUUID()); attacked.remove(player.getUUID());
            armor.keySet().removeIf(key -> key.startsWith(player.getUUID() + ":"));
        }
    }

    @EventHandler private void blockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || mc.level == null || !nearFight(Vec3.atCenterOf(event.pos))) return;
        if (!event.oldState.is(Blocks.OBSIDIAN) && !event.newState.is(Blocks.OBSIDIAN)
            && !event.oldState.is(Blocks.BEDROCK) && !event.newState.is(Blocks.BEDROCK)) return;
        JsonObject data = new JsonObject(); data.addProperty("before", BuiltInRegistries.BLOCK.getKey(event.oldState.getBlock()).toString());
        data.addProperty("after", BuiltInRegistries.BLOCK.getKey(event.newState.getBlock()).toString());
        Intent own = ownBlockIntents.remove(event.pos);
        if (own != null) data.addProperty("ourIntent", own.role());
        JsonArray nearby = new JsonArray();
        for (Player player : mc.level.players()) if (player != mc.player && relevant(player) && player.distanceToSqr(Vec3.atCenterOf(event.pos)) <= 16)
            nearby.add(player.getUUID().toString());
        if (!nearby.isEmpty()) data.add("potentialOpponentCoverOrBaseNear", nearby);
        event("block_update", "observed", mc.player, Vec3.atCenterOf(event.pos), data);
        if (own == null && !nearby.isEmpty() && event.newState.is(Blocks.OBSIDIAN))
            event("opponent_cover_or_base_candidate", "inferred", null, Vec3.atCenterOf(event.pos), data.deepCopy());
    }

    @EventHandler private void packet(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundExplodePacket explosion) {
            JsonObject data = new JsonObject(); data.addProperty("radius", explosion.radius()); data.addProperty("blockCount", explosion.blockCount());
            this.event("explosion_packet", "observed", null, explosion.center(), data);
        } else if (event.packet instanceof ClientboundDamageEventPacket damagePacket) {
            mc.execute(() -> {
                if (active != this || mc.level == null) return;
                Entity victim = mc.level.getEntity(damagePacket.entityId());
                if (!(victim instanceof Player player) || player != mc.player && !relevant(player)) return;
                JsonObject data = new JsonObject(); data.addProperty("directEntityId", damagePacket.sourceDirectId());
                data.addProperty("causingEntityId", damagePacket.sourceCauseId());
                try {
                    var cause = damagePacket.getSource(mc.level);
                    boolean ours = ownCrystals.containsKey(damagePacket.sourceDirectId());
                    boolean crystal = knownCrystals.containsKey(damagePacket.sourceDirectId()) || cause.getDirectEntity() instanceof EndCrystal;
                    damage.put(player.getUUID(), new Damage(System.currentTimeMillis(), cause.getMsgId(), damagePacket.sourceDirectId(), crystal, ours));
                    data.addProperty("cause", cause.getMsgId()); data.addProperty("crystal", crystal); data.addProperty("ourCrystal", ours);
                } catch (RuntimeException error) { data.addProperty("decodeError", error.getClass().getSimpleName()); }
                this.event("damage_packet", "observed", player, data);
            });
        } else if (event.packet instanceof ClientboundEntityEventPacket entityEvent) {
            byte id = entityEvent.getEventId();
            if (id != EntityEvent.DEATH && id != EntityEvent.PROTECTED_FROM_DEATH && id != EntityEvent.HEAD_BREAK
                && id != EntityEvent.CHEST_BREAK && id != EntityEvent.LEGS_BREAK && id != EntityEvent.FEET_BREAK
                && id != EntityEvent.OFFHAND_BREAK && id != EntityEvent.MAINHAND_BREAK) return;
            mc.execute(() -> {
                if (active != this || mc.level == null) return;
                Entity entity = entityEvent.getEntity(mc.level);
                if (!(entity instanceof Player player) || player != mc.player && !relevant(player)) return;
                String kind = id == EntityEvent.DEATH ? "death" : id == EntityEvent.PROTECTED_FROM_DEATH ? "totem_pop" : "equipment_break";
                JsonObject data = new JsonObject(); data.addProperty("eventId", id);
                this.event(kind, "observed", player, data);
                if (id == EntityEvent.DEATH && System.currentTimeMillis() - attacked.getOrDefault(player.getUUID(), 0L) < 5_000)
                    this.event("possible_kill", "inferred", player, data.deepCopy());
            });
        }
    }

    private boolean nearFight(Vec3 point) {
        if (mc.player != null && mc.player.distanceToSqr(point) <= 64 * 64) return true;
        if (mc.level == null) return false;
        for (Player player : mc.level.players()) if (player != mc.player && relevant(player) && player.distanceToSqr(point) <= 32 * 32) return true;
        return false;
    }

    private void event(String kind, String evidence, Entity actor, JsonObject data) {
        event(kind, evidence, actor, actor == null ? null : actor.position(), data);
    }

    private void event(String kind, String evidence, Entity actor, Vec3 position, JsonObject data) {
        store.append(new FightEventStore.Event(System.currentTimeMillis(), tick, kind, evidence,
            actor == null ? null : actor.getUUID().toString(), actor == null ? null : actor.getName().getString(),
            position == null ? null : position.x, position == null ? null : position.y, position == null ? null : position.z, data.toString()));
    }

    @Override public void close() {
        if (closed) return;
        closed = true; active = null;
        MonocleClient.EVENT_BUS.unsubscribe(this);
        event("session_end", "observed", mc.player, new JsonObject());
        store.close();
    }
}
