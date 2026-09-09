/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.AutoReconnect;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.entity.DamageUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.util.CommonColors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

public class AutoLog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEntities = settings.createGroup("Entities");
    private final SettingGroup sgSupplies = settings.createGroup("Supply / Gear Guard");

    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action").description("Disconnect protects by leaving; Alert Only lets you test thresholds without disconnecting or changing Auto Reconnect.")
        .defaultValue(Action.Disconnect).build()
    );

    private final Setting<Integer> minimumTotems = sgSupplies.add(new IntSetting.Builder()
        .name("minimum-totems").description("Trigger below this many totems across inventory and offhand, excluding containers. Zero disables this check.")
        .defaultValue(0).range(0, 36).sliderRange(0, 9).build()
    );

    private final Setting<Integer> gearReserve = sgSupplies.add(new IntSetting.Builder()
        .name("gear-durability-percent").description("Trigger when any equipped armor piece or elytra falls below this remaining durability percentage. Empty slots are ignored. Zero disables.")
        .defaultValue(0).range(0, 100).sliderRange(0, 25).build()
    );

    private final Setting<Double> reserveDelay = sgSupplies.add(new DoubleSetting.Builder()
        .name("reserve-confirmation-seconds").description("Require a continuous shortage before acting, to tolerate inventory swaps. Does not delay health or damage checks.")
        .defaultValue(1).range(0, 10).sliderRange(0, 5).build()
    );

    private final Setting<Integer> playerRange = sgEntities.add(new IntSetting.Builder()
        .name("untrusted-player-range").description("Distance for Only Trusted. Zero uses all loaded players, preserving the original behavior.")
        .defaultValue(0).range(0, 512).sliderRange(0, 128).build()
    );

    private final Setting<Double> playerDelay = sgEntities.add(new DoubleSetting.Builder()
        .name("untrusted-confirmation-seconds").description("Require continuous untrusted-player presence for this long. Zero acts immediately; lethal-damage checks never wait.")
        .defaultValue(0).range(0, 10).sliderRange(0, 5).build()
    );

    private final Setting<Integer> health = sgGeneral.add(new IntSetting.Builder()
        .name("health")
        .description("Automatically disconnects when health is lower or equal to this value. Set to 0 to disable.")
        .defaultValue(6)
        .range(0, 19)
        .sliderMax(19)
        .build()
    );

    private final Setting<Boolean> smart = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-incoming-damage")
        .description("Disconnects when it detects you're about to take enough damage to set you under the 'health' setting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> totemPops = sgGeneral.add(new IntSetting.Builder()
        .name("totem-pops")
        .description("Disconnects when you have popped this many totems. Set to 0 to disable.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> onlyTrusted = sgGeneral.add(new BoolSetting.Builder()
        .name("only-trusted")
        .description("Disconnects when a player not on your friends list appears in render distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> instantDeath = sgGeneral.add(new BoolSetting.Builder()
        .name("32K")
        .description("Disconnects when a player near you can instantly kill you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> smartToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-toggle")
        .description("Disables Auto Log after a low-health logout. WILL re-enable once you heal.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleOff = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-off")
        .description("Disables Auto Log after usage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleAutoReconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-auto-reconnect")
        .description("Whether to disable Auto Reconnect after a logout.")
        .defaultValue(true)
        .build()
    );

    // Entities

    private final Setting<Set<EntityType<?>>> entities = sgEntities.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Disconnects when a specified entity is present within a specified range.")
        .defaultValue(EntityTypes.END_CRYSTAL)
        .build()
    );

    private final Setting<Boolean> useTotalCount = sgEntities.add(new BoolSetting.Builder()
        .name("use-total-count")
        .description("Toggle between counting the total number of all selected entities or each entity individually.")
        .defaultValue(true)
        .visible(() -> !entities.get().isEmpty())
        .build());

    private final Setting<Integer> combinedEntityThreshold = sgEntities.add(new IntSetting.Builder()
        .name("combined-entity-threshold")
        .description("The minimum total number of selected entities that must be near you before disconnection occurs.")
        .defaultValue(10)
        .min(1)
        .sliderMax(32)
        .visible(() -> useTotalCount.get() && !entities.get().isEmpty())
        .build()
    );

    private final Setting<Integer> individualEntityThreshold = sgEntities.add(new IntSetting.Builder()
        .name("individual-entity-threshold")
        .description("The minimum number of entities individually that must be near you before disconnection occurs.")
        .defaultValue(2)
        .min(1)
        .sliderMax(16)
        .visible(() -> !useTotalCount.get() && !entities.get().isEmpty())
        .build()
    );

    private final Setting<Integer> range = sgEntities.add(new IntSetting.Builder()
        .name("range")
        .description("How close an entity has to be to you before you disconnect.")
        .defaultValue(5)
        .min(1)
        .sliderMax(16)
        .visible(() -> !entities.get().isEmpty())
        .build()
    );

    // Declaring variables outside the loop for better efficiency
    private final Object2IntMap<EntityType<?>> entityCounts = new Object2IntOpenHashMap<>();

    private int pops;
    private boolean healthListenerActive;
    private net.minecraft.client.multiplayer.ClientLevel observedWorld;
    private int reserveSince = -1, strangerSince = -1, lastWarningTick = -200;
    private volatile int activationEpoch;
    private boolean disconnecting;
    private String lastTrigger = "None", guardStatus = "Inactive";

    public AutoLog() {
        super(Categories.Combat, "auto-log", "Survival guard: monitor health, threats, totem reserves and equipped gear; disconnect or test with alerts only.");
    }

    @Override
    public void onActivate() {
        pops = 0;
        activationEpoch++;
        observedWorld = null;
        disconnecting = false;
        guardStatus = "Armed";
        disableHealthListener();
    }

    @Override public void onDeactivate() { activationEpoch++; observedWorld = null; guardStatus = "Inactive"; }

    private void checkWorld() {
        if (observedWorld == mc.level) return;
        observedWorld = mc.level;
        pops = 0;
        reserveSince = strangerSince = -1;
        lastWarningTick = -200;
        disconnecting = false;
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != EntityEvent.PROTECTED_FROM_DEATH) return;

        var world = mc.level;
        int epoch = activationEpoch;
        mc.execute(() -> {
            if (!isActive() || epoch != activationEpoch || mc.level != world || mc.player == null) return;
            checkWorld();
            Entity entity = p.getEntity(world);
            if (entity != mc.player) return;
            pops++;
            if (totemPops.get() > 0 && pops >= totemPops.get()) trigger(Component.literal("Popped " + pops + " totems."), false);
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) { observedWorld = null; guardStatus = "Waiting for world"; return; }
        checkWorld();
        if (disconnecting) return;
        guardStatus = action.get() == Action.AlertOnly ? "Monitoring (alerts only)" : "Armed";

        float totalHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (mc.player.isDeadOrDying()) {
            toggle();
            return;
        }

        if (health.get() > 0 && totalHealth <= health.get()) {
            trigger(Component.literal("Health was at or below " + health.get() + "."), true);
            return;
        }

        if (health.get() > 0 && smart.get() && totalHealth - PlayerUtils.possibleHealthReductions() <= health.get()) {
            trigger(Component.literal("Predicted health was at or below " + health.get() + "."), true);
            return;
        }

        String shortage = reserveReason();
        if (shortage == null) reserveSince = -1;
        else {
            if (reserveSince < 0) reserveSince = mc.player.tickCount;
            guardStatus = "Confirming reserve warning";
            if (confirmed(mc.player.tickCount, reserveSince, reserveDelay.get())) { trigger(Component.literal(shortage), false); return; }
        }

        if (!onlyTrusted.get()) strangerSince = -1;
        if (!onlyTrusted.get() && !instantDeath.get() && entities.get().isEmpty())
            return; // only check all entities if needed

        int totalEntities = 0;
        entityCounts.clear();
        Player nearestStranger = null;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;

            if (entity instanceof Player player) {
                if (onlyTrusted.get() && player.isAlive() && !player.isSpectator() && !Friends.get().isFriend(player)
                    && (playerRange.get() == 0 || PlayerUtils.isWithin(player, playerRange.get()))) {
                    if (nearestStranger == null || player.distanceToSqr(mc.player) < nearestStranger.distanceToSqr(mc.player)) nearestStranger = player;
                }

                if (instantDeath.get()
                    && PlayerUtils.isWithin(player, 8)
                    && DamageUtils.getAttackDamage(player, mc.player) >= totalHealth) {
                    trigger(Component.literal("Anti-32k measures."), false);
                    return;
                }
            }

            if (!entities.get().isEmpty() && PlayerUtils.isWithin(entity, range.get()) && entities.get().contains(entity.getType())) {
                totalEntities++;
                if (!useTotalCount.get()) {
                    entityCounts.put(entity.getType(), entityCounts.getOrDefault(entity.getType(), 0) + 1);
                }
            }
        }

        if (nearestStranger == null) strangerSince = -1;
        else {
            if (strangerSince < 0) strangerSince = mc.player.tickCount;
            guardStatus = "Confirming nearby stranger";
            if (confirmed(mc.player.tickCount, strangerSince, playerDelay.get())) {
                trigger(Component.literal("Untrusted player " + nearestStranger.getName().getString() + " within "
                    + Math.round(nearestStranger.distanceTo(mc.player)) + " blocks."), false);
                return;
            }
        }

        if (useTotalCount.get() && totalEntities >= combinedEntityThreshold.get()) {
            trigger(Component.literal("Total number of selected entities within range exceeded the limit."), false);
        } else if (!useTotalCount.get()) {
            for (Object2IntMap.Entry<EntityType<?>> entry : entityCounts.object2IntEntrySet()) {
                if (entry.getIntValue() >= individualEntityThreshold.get()) {
                    trigger(Component.literal("Number of " + entry.getKey().getDescription().getString() + " within range exceeded the limit."), false);
                    return;
                }
            }
        }
    }

    private void trigger(Component reason, boolean reenableWhenHealthy) {
        if (!isActive() || disconnecting || mc.player == null) return;
        reserveSince = strangerSince = -1;
        lastTrigger = reason.getString();
        guardStatus = action.get() == Action.AlertOnly ? "Warning" : "Disconnecting";
        if (action.get() == Action.AlertOnly) {
            if (warningDue(mc.player.tickCount, lastWarningTick)) {
                warning("Survival guard: %s", lastTrigger);
                lastWarningTick = mc.player.tickCount;
            }
            return;
        }
        disconnecting = true;
        MutableComponent text = Component.literal("[Monocle Survival Guard] ");
        text.append(reason);

        AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect.isActive() && toggleAutoReconnect.get()) {
            text.append(Component.literal("\n\nINFO - AutoReconnect was disabled").withColor(CommonColors.GRAY));
            autoReconnect.toggle();
        }

        mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(text));

        if (reenableWhenHealthy && smartToggle.get()) {
            if (isActive()) toggle();
            enableHealthListener();
        } else if (toggleOff.get() && isActive()) {
            toggle();
        }
    }

    private String reserveReason() {
        if (minimumTotems.get() > 0) {
            int totems = countTotems(mc.player.getInventory().getNonEquipmentItems(), mc.player.getOffhandItem());
            if (totems < minimumTotems.get()) return "Totem reserve: " + totems + " remaining; minimum " + minimumTotems.get() + ".";
        }
        if (gearReserve.get() == 0) return null;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (gearLow(stack, gearReserve.get())) return stack.getHoverName().getString() + " durability below " + gearReserve.get() + "% (" + (stack.getMaxDamage() - stack.getDamageValue()) + " remaining).";
        }
        return null;
    }

    static int countTotems(Iterable<ItemStack> inventory, ItemStack offhand) {
        int count = offhand.is(Items.TOTEM_OF_UNDYING) ? offhand.getCount() : 0;
        for (ItemStack stack : inventory) if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        return count;
    }

    static boolean gearLow(ItemStack stack, int reserve) {
        return reserve > 0 && !stack.isEmpty() && stack.isDamageableItem()
            && (long) (stack.getMaxDamage() - stack.getDamageValue()) * 100 < (long) stack.getMaxDamage() * reserve;
    }

    static boolean confirmed(int tick, int since, double seconds) { return since >= 0 && tick - since >= Math.ceil(seconds * 20); }
    static boolean warningDue(int tick, int last) { return tick < last || tick - last >= 200; }

    @Override public String getInfoString() { return guardStatus; }

    @Override public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        list.add(theme.label("Survival Guard · " + guardStatus));
        list.add(theme.label("Last trigger: " + lastTrigger, 500));
        list.add(theme.label("Totems popped this activation/world: " + pops));
        list.add(theme.label("Health is measured in points (2 = one heart). Predictions are estimates.\nLogout cannot guarantee survival on combat-tag servers.\nUse Alert Only to test new thresholds first.", 500));
        return list;
    }

    public enum Action { Disconnect, AlertOnly }

    private class StaticListener {
        @EventHandler
        private void healthListener(TickEvent.Post event) {
            if (isActive()) disableHealthListener();

            else if (Utils.canUpdate()
                && !mc.player.isDeadOrDying()
                && mc.player.getHealth() + mc.player.getAbsorptionAmount() > health.get()) {
                info("Player health greater than minimum, re-enabling module.");
                toggle();
                disableHealthListener();
            }
        }
    }

    private final StaticListener staticListener = new StaticListener();

    private void enableHealthListener() {
        if (healthListenerActive) return;
        healthListenerActive = true;
        MonocleClient.EVENT_BUS.subscribe(staticListener);
    }

    private void disableHealthListener() {
        if (!healthListenerActive) return;
        healthListenerActive = false;
        MonocleClient.EVENT_BUS.unsubscribe(staticListener);
    }
}
