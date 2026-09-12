/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.misc;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import dev.monocle.client.events.entity.EntityAddedEvent;
import dev.monocle.client.events.entity.EntityRemovedEvent;
import dev.monocle.client.events.game.GameJoinedEvent;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.entity.fakeplayer.FakePlayerEntity;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.render.Notifications;
import dev.monocle.client.utils.render.NotificationFeed.Severity;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ArrayListDeque;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static dev.monocle.client.utils.player.ChatUtils.formatCoords;

public class Notifier extends Module {
    private final SettingGroup sgTotemPops = settings.createGroup("Totem Pops");
    private final SettingGroup sgVisualRange = settings.createGroup("Visual Range");
    private final SettingGroup sgPearl = settings.createGroup("Pearl");
    private final SettingGroup sgJoinsLeaves = settings.createGroup("Joins/Leaves");
    private final Setting<Notifications.Output> totemOutput = output(sgTotemPops);
    private final Setting<Notifications.Output> rangeOutput = output(sgVisualRange);
    private final Setting<Notifications.Output> pearlOutput = output(sgPearl);
    private final Setting<Notifications.Output> joinOutput = output(sgJoinsLeaves);

    private Setting<Notifications.Output> output(SettingGroup group) {
        return group.add(new EnumSetting.Builder<Notifications.Output>().name("output")
            .description("Send this category to chat, the shared notification feed, or both.")
            .defaultValue(Notifications.Output.Feed).build());
    }

    private void notify(Setting<Notifications.Output> output, String key, Severity severity, String message, Object... args) {
        Notifications.send(output.get(), "Notifier", key, severity, Component.literal(String.format(Locale.ROOT, message, args)));
    }

    public static boolean ignoredPop(boolean self, boolean friend, boolean ignoreSelf, boolean ignoreFriends, boolean ignoreOthers) {
        return self ? ignoreSelf : friend ? ignoreFriends : ignoreOthers;
    }

    // Totem Pops

    private final Setting<Boolean> totemPops = sgTotemPops.add(new BoolSetting.Builder()
        .name("totem-pops")
        .description("Notifies you when a player pops a totem.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> totemsDistanceCheck = sgTotemPops.add(new BoolSetting.Builder()
        .name("distance-check")
        .description("Limits the distance in which the pops are recognized.")
        .defaultValue(false)
        .visible(totemPops::get)
        .build()
    );

    private final Setting<Integer> totemsDistance = sgTotemPops.add(new IntSetting.Builder()
        .name("player-radius")
        .description("The radius in which to log totem pops.")
        .defaultValue(30)
        .sliderRange(1, 50)
        .range(1, 100)
        .visible(() -> totemPops.get() && totemsDistanceCheck.get())
        .build()
    );

    private final Setting<Boolean> totemsIgnoreOwn = sgTotemPops.add(new BoolSetting.Builder()
        .name("ignore-own")
        .description("Ignores your own totem pops.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> totemsIgnoreFriends = sgTotemPops.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignores friends totem pops.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> totemsIgnoreOthers = sgTotemPops.add(new BoolSetting.Builder()
        .name("ignore-others")
        .description("Ignores other players totem pops.")
        .defaultValue(false)
        .build()
    );

    // Visual Range

    private final Setting<Boolean> visualRange = sgVisualRange.add(new BoolSetting.Builder()
        .name("visual-range")
        .description("Notifies you when an entity enters your render distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Event> event = sgVisualRange.add(new EnumSetting.Builder<Event>()
        .name("event")
        .description("When to log the entities.")
        .defaultValue(Event.Both)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgVisualRange.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Which entities to notify about.")
        .defaultValue(EntityTypes.PLAYER)
        .build()
    );

    private final Setting<Boolean> visualRangeIgnoreFriends = sgVisualRange.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignores friends.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> visualRangeIgnoreFakes = sgVisualRange.add(new BoolSetting.Builder()
        .name("ignore-fake-players")
        .description("Ignores fake players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> visualMakeSound = sgVisualRange.add(new BoolSetting.Builder()
        .name("sound")
        .description("Emits a sound effect on enter / leave")
        .defaultValue(true)
        .build()
    );

    // Pearl

    private final Setting<Boolean> pearl = sgPearl.add(new BoolSetting.Builder()
        .name("pearl")
        .description("Notifies you when a player is teleported using an ender pearl.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pearlIgnoreOwn = sgPearl.add(new BoolSetting.Builder()
        .name("ignore-own")
        .description("Ignores your own pearls.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pearlIgnoreFriends = sgPearl.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignores friends pearls.")
        .defaultValue(false)
        .build()
    );

    // Joins/Leaves

    private final Setting<JoinLeaveModes> joinsLeavesMode = sgJoinsLeaves.add(new EnumSetting.Builder<JoinLeaveModes>()
        .name("player-joins-leaves")
        .description("How to handle player join/leave notifications.")
        .defaultValue(JoinLeaveModes.None)
        .build()
    );

    private final Setting<Integer> notificationDelay = sgJoinsLeaves.add(new IntSetting.Builder()
        .name("notification-delay")
        .description("How long to wait in ticks before posting the next join/leave notification in your chat.")
        .range(0, 1000)
        .sliderRange(0, 100)
        .defaultValue(0)
        .build()
    );

    private final Setting<Boolean> simpleNotifications = sgJoinsLeaves.add(new BoolSetting.Builder()
        .name("simple-notifications")
        .description("Display join/leave notifications without a prefix, to reduce chat clutter.")
        .defaultValue(true)
        .build()
    );

    private int timer;
    private final Object2IntMap<UUID> totemPopMap = new Object2IntOpenHashMap<>();
    private final Int2ObjectMap<Vec3> pearlStartPosMap = new Int2ObjectOpenHashMap<>();
    private final ArrayListDeque<MutableComponent> messageQueue = new ArrayListDeque<>();
    private final Map<UUID, String> playerNames = new HashMap<>();
    private record Pending(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.Connection connection, long revision) { }
    private final java.util.concurrent.ArrayBlockingQueue<Pending> packets = new java.util.concurrent.ArrayBlockingQueue<>(128);

    public Notifier() {
        super(Categories.Misc, "notifier", "Notifies you of different events.");
    }

    @Override
    public dev.monocle.client.gui.widgets.WWidget getWidget(dev.monocle.client.gui.GuiTheme theme) {
        var list = theme.verticalList();
        list.add(theme.label("Appearance: Config → Notification Feed. Close the GUI to see the feed.", 450));
        list.add(theme.button("Preview Notification Feed")).widget().action = () -> {
            Notifications.post("Monocle", "preview-ready", Severity.Success, "Notification feed ready.");
            Notifications.post("Highway Builder · Preview", "preview-warning", Severity.Warning, "Supplies running low. This is only a preview.");
            Notifications.post("Notifier · Preview", "preview-range", Severity.Info, "ExamplePlayer entered visual range (42m away).");
        };
        list.add(theme.button("Recent Notifications")).widget().action = () -> mc.gui.setScreen(new dev.monocle.client.gui.WindowScreen(theme, "Recent Notifications") {
            @Override public void initWidgets() {
                var history = Notifications.FEED.history();
                add(theme.label("Session-only snapshot · newest first · last 100 updates", 450));
                if (history.isEmpty()) add(theme.label("No feed notifications in this world yet."));
                for (var entry : history.reversed()) add(theme.label(entry.source() + " · " + entry.severity() + "\n" + entry.text(), 450));
                add(theme.button("Clear History and Feed")).widget().action = () -> { Notifications.FEED.clear(); reload(); };
            }
        });
        return list;
    }

    // Visual Range

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!event.entity.getUUID().equals(mc.player.getUUID()) && entities.get().contains(event.entity.getType()) && visualRange.get() && this.event.get() != Event.Despawn) {
            if (event.entity instanceof Player player) {
                if ((!visualRangeIgnoreFriends.get() || !Friends.get().isFriend(player)) && (!visualRangeIgnoreFakes.get() || !(event.entity instanceof FakePlayerEntity))) {
                    notify(rangeOutput, "range-" + player.getUUID(), Severity.Warning, "%s entered visual range (%.0fm away).", player.getName().getString(), player.distanceTo(mc.player));

                    if (visualMakeSound.get() && rangeOutput.get() == Notifications.Output.Chat)
                        mc.level.playSound(mc.player, mc.player, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.AMBIENT, 3.0F, 1.0F);
                }
            } else {
                MutableComponent text = Component.literal(event.entity.getType().getDescription().getString()).withStyle(ChatFormatting.WHITE);
                text.append(Component.literal(" has spawned at ").withStyle(ChatFormatting.GRAY));
                text.append(formatCoords(event.entity.position()));
                text.append(Component.literal(".").withStyle(ChatFormatting.GRAY));
                Notifications.send(rangeOutput.get(), "Notifier", "range-" + event.entity.getUUID(), Severity.Info, text);
            }
        }

        if (pearl.get() && event.entity instanceof ThrownEnderpearl pearlEntity) {
            pearlStartPosMap.put(pearlEntity.getId(), new Vec3(pearlEntity.getX(), pearlEntity.getY(), pearlEntity.getZ()));
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (!event.entity.getUUID().equals(mc.player.getUUID()) && entities.get().contains(event.entity.getType()) && visualRange.get() && this.event.get() != Event.Spawn) {
            if (event.entity instanceof Player player) {
                if ((!visualRangeIgnoreFriends.get() || !Friends.get().isFriend(player)) && (!visualRangeIgnoreFakes.get() || !(event.entity instanceof FakePlayerEntity))) {
                    notify(rangeOutput, "range-" + player.getUUID(), Severity.Info, "%s left visual range.", player.getName().getString());

                    if (visualMakeSound.get() && rangeOutput.get() == Notifications.Output.Chat)
                        mc.level.playSound(mc.player, mc.player, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.AMBIENT, 3.0F, 1.0F);
                }
            } else {
                MutableComponent text = Component.literal(event.entity.getType().getDescription().getString()).withStyle(ChatFormatting.WHITE);
                text.append(Component.literal(" has despawned at ").withStyle(ChatFormatting.GRAY));
                text.append(formatCoords(event.entity.position()));
                text.append(Component.literal(".").withStyle(ChatFormatting.GRAY));
                Notifications.send(rangeOutput.get(), "Notifier", "range-" + event.entity.getUUID(), Severity.Info, text);
            }
        }

        if (pearl.get()) {
            Entity e = event.entity;
            int i = e.getId();
            Vec3 thrownPos = pearlStartPosMap.remove(i);
            if (thrownPos != null) {
                ThrownEnderpearl pearl = (ThrownEnderpearl) e;
                if (pearl.getOwner() != null && pearl.getOwner() instanceof Player p) {
                    double d = thrownPos.distanceTo(e.position());
                    if ((!Friends.get().isFriend(p) || !pearlIgnoreFriends.get()) && (!p.equals(mc.player) || !pearlIgnoreOwn.get())) {
                        notify(pearlOutput, "", Severity.Info, "%s's pearl last seen at %d, %d, %d (%.1fm away, travelled %.1fm).", pearl.getOwner().getName().getString(), pearl.blockPosition().getX(), pearl.blockPosition().getY(), pearl.blockPosition().getZ(), pearl.distanceTo(mc.player), d);
                    }
                }
            }
        }
    }

    // Totem Pops && Joins/Leaves

    @Override
    public void onActivate() {
        packets.clear();
        messageQueue.clear();
        playerNames.clear();
        if (mc.getConnection() != null) for (var p : mc.getConnection().getOnlinePlayers()) playerNames.put(p.getProfile().id(), p.getProfile().name());
        totemPopMap.clear();
        pearlStartPosMap.clear();
    }

    @Override
    public void onDeactivate() {
        timer = 0;
        messageQueue.clear();
        packets.clear();
        playerNames.clear();
    }

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        timer = 0;
        onActivate();
    }

    @EventHandler
    private void onGameLeave(GameLeftEvent event) {
        onDeactivate();
        totemPopMap.clear();
        pearlStartPosMap.clear();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerInfoUpdatePacket || event.packet instanceof ClientboundPlayerInfoRemovePacket
            || event.packet instanceof ClientboundEntityEventPacket) {
            packets.offer(new Pending(event.packet, event.connection, activationRevision()));
        }
    }

    private void handlePacket(net.minecraft.network.protocol.Packet<?> received) {
        switch (received) {
            case
                ClientboundPlayerInfoUpdatePacket packet -> {
                if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                    createJoinNotifications(packet);
                }
            }
            case
                ClientboundPlayerInfoRemovePacket packet ->
                createLeaveNotification(packet);

            case
                ClientboundEntityEventPacket packet when totemPops.get() && packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH && packet.getEntity(mc.level) instanceof Player entity -> {
                if (ignoredPop(entity.equals(mc.player), Friends.get().isFriend(entity), totemsIgnoreOwn.get(),
                    totemsIgnoreFriends.get(), totemsIgnoreOthers.get())) return;

                synchronized (totemPopMap) {
                    int pops = totemPopMap.getOrDefault(entity.getUUID(), 0);
                    totemPopMap.put(entity.getUUID(), ++pops);

                    double distance = PlayerUtils.distanceTo(entity);
                    if (totemsDistanceCheck.get() && distance > totemsDistance.get()) return;

                    notify(totemOutput, "totem-" + entity.getUUID(), Severity.Warning, "%s popped %d %s.", entity.getName().getString(), pops, pops == 1 ? "totem" : "totems");
                }
            }
            default -> {
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        for (int i = 0; i < 64; i++) {
            Pending pending = packets.poll();
            if (pending == null) break;
            if (pending.revision() == activationRevision() && mc.getConnection() != null
                && pending.connection() == mc.getConnection().getConnection()) handlePacket(pending.packet());
        }
        if (joinsLeavesMode.get() != JoinLeaveModes.None) {
            timer++;
            while (timer >= notificationDelay.get() && !messageQueue.isEmpty()) {
                timer = 0;
                Notifications.send(joinOutput.get(), "Notifier", "", Severity.Info, messageQueue.removeFirst());
            }
        }

        if (!totemPops.get()) return;
        synchronized (totemPopMap) {
            for (Player player : mc.level.players()) {
                if (!totemPopMap.containsKey(player.getUUID())) continue;

                if (player.deathTime > 0 || player.getHealth() <= 0) {
                    int pops = totemPopMap.removeInt(player.getUUID());

                    notify(totemOutput, "totem-" + player.getUUID(), Severity.Error, "%s died after popping %d %s.", player.getName().getString(), pops, pops == 1 ? "totem" : "totems");
                }
            }
        }
    }

    private void createJoinNotifications(ClientboundPlayerInfoUpdatePacket packet) {
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
            if (entry.profile() == null) continue;
            if (playerNames.put(entry.profile().id(), entry.profile().name()) != null) continue;
            if (joinsLeavesMode.get() != JoinLeaveModes.Both && joinsLeavesMode.get() != JoinLeaveModes.Joins) continue;
            if (messageQueue.size() >= 100) messageQueue.removeFirst();

            if (simpleNotifications.get()) {
                messageQueue.addLast(Component.literal(
                    ChatFormatting.GRAY + "["
                        + ChatFormatting.GREEN + "+"
                        + ChatFormatting.GRAY + "] "
                        + entry.profile().name()
                ));
            } else {
                messageQueue.addLast(Component.literal(
                    ChatFormatting.WHITE
                        + entry.profile().name()
                        + ChatFormatting.GRAY + " joined."
                ));
            }
        }
    }

    private void createLeaveNotification(ClientboundPlayerInfoRemovePacket packet) {
        if (mc.getConnection() == null) return;

        for (UUID id : packet.profileIds()) {
            String name = playerNames.remove(id);
            if (name == null || joinsLeavesMode.get() != JoinLeaveModes.Both && joinsLeavesMode.get() != JoinLeaveModes.Leaves) continue;
            if (messageQueue.size() >= 100) messageQueue.removeFirst();

            if (simpleNotifications.get()) {
                messageQueue.addLast(Component.literal(
                    ChatFormatting.GRAY + "["
                        + ChatFormatting.RED + "-"
                        + ChatFormatting.GRAY + "] "
                        + name
                ));
            } else {
                messageQueue.addLast(Component.literal(
                    ChatFormatting.WHITE
                        + name
                        + ChatFormatting.GRAY + " left."
                ));
            }
        }
    }

    public enum Event {
        Spawn,
        Despawn,
        Both
    }

    public enum JoinLeaveModes {
        None, Joins, Leaves, Both
    }
}
