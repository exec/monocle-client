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
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.util.CommonColors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;

import java.util.Set;

public class AutoLog extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgEntities = settings.createGroup("Entities");

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

    public AutoLog() {
        super(Categories.Combat, "auto-log", "Automatically disconnects you when certain requirements are met.");
    }

    @Override
    public void onActivate() {
        pops = 0;
        disableHealthListener();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != EntityEvent.PROTECTED_FROM_DEATH) return;

        Entity entity = p.getEntity(mc.level);
        if (entity == null || !entity.equals(mc.player)) return;

        pops++;
        if (totemPops.get() > 0 && pops >= totemPops.get()) {
            trigger(Component.literal("Popped " + pops + " totems."), false);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

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

        if (!onlyTrusted.get() && !instantDeath.get() && entities.get().isEmpty())
            return; // only check all entities if needed

        int totalEntities = 0;
        entityCounts.clear();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;

            if (entity instanceof Player player) {
                if (onlyTrusted.get() && !Friends.get().isFriend(player)) {
                    trigger(Component.literal("Non-trusted player '" + ChatFormatting.RED + player.getName().getString() + ChatFormatting.WHITE + "' appeared in your render distance."), false);
                    return;
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
        MutableComponent text = Component.literal("[AutoLog] ");
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
