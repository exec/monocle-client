/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.render;

import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.world.EncounterHistory;
import dev.monocle.client.utils.world.EncounterHistory.*;
import dev.monocle.client.systems.waypoints.Waypoint;
import dev.monocle.client.systems.waypoints.Waypoints;
import dev.monocle.client.utils.world.Dimension;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import dev.monocle.client.events.render.Render2DEvent;
import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.renderer.Renderer2D;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.renderer.text.TextRenderer;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.render.NametagUtils;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

public class LogoutSpots extends Module {
    private static final Color GREEN = new Color(25, 225, 25);
    private static final Color ORANGE = new Color(225, 105, 25);
    private static final Color RED = new Color(225, 25, 25);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("The scale.")
        .defaultValue(1)
        .min(0)
        .build()
    );

    private final Setting<Boolean> fullHeight = sgGeneral.add(new BoolSetting.Builder()
        .name("full-height")
        .description("Displays the height as the player's full height.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color.")
        .defaultValue(new SettingColor(255, 0, 255, 55))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color.")
        .defaultValue(new SettingColor(255, 0, 255))
        .build()
    );

    private final Setting<SettingColor> nameColor = sgRender.add(new ColorSetting.Builder()
        .name("name-color")
        .description("The name color.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> nameBackgroundColor = sgRender.add(new ColorSetting.Builder()
        .name("name-background-color")
        .description("The name background color.")
        .defaultValue(new SettingColor(0, 0, 0, 75))
        .build()
    );


    private final Setting<Integer> expiryHours = sgGeneral.add(new IntSetting.Builder()
        .name("history-expiry-hours").description("Expire sightings after this many hours, including offline time.")
        .defaultValue(24).range(1, 720).build());
    private final Setting<Integer> historyLimit = sgGeneral.add(new IntSetting.Builder()
        .name("history-limit").description("Maximum saved sightings across all servers and dimensions.")
        .defaultValue(500).range(10, 2000).build());
    private final Setting<Boolean> showLastSeen = sgRender.add(new BoolSetting.Builder()
        .name("show-last-seen").description("Also mark players who left tracking range without a nearby player-list departure.")
        .defaultValue(false).build());
    private final Setting<Boolean> notifyDepartures = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-departures").description("Notify when a recently tracked player leaves both tracking range and the player list.")
        .defaultValue(true).build());
    private final EncounterHistory history = new EncounterHistory();
    private List<Entry> cachedEntries = List.of();
    private String server = "", dimension = "";
    private Object world;
    private int sampleTicks;
    private static final Vector3d pos = new Vector3d();

    public LogoutSpots() {
        super(Categories.Render, "logout-spots", "Last-seen encounter notebook and evidence-labelled player departure markers.");
        lineColor.onChanged();
    }

    @Override public void onActivate() { history.resetTracking(); cachedEntries = List.of(); world = null; sampleTicks = 0; }
    @Override public void onDeactivate() { history.resetTracking(); cachedEntries = List.of(); world = null; }
    @EventHandler private void onLeft(GameLeftEvent event) { onDeactivate(); }

    private String currentServer() {
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            return "local:" + ((dev.monocle.client.mixin.MinecraftServerAccessor) mc.getSingleplayerServer())
                .monocle$getStorageSource().getDimensionPath(net.minecraft.world.level.Level.OVERWORLD).toAbsolutePath().normalize();
        }
        return mc.getCurrentServer() != null ? mc.getCurrentServer().ip : Utils.getWorldName();
    }

    private boolean currentScope() {
        return mc.level != null && server.equals(currentServer()) && dimension.equals(mc.level.dimension().identifier().toString());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.getConnection() == null) return;
        if (world != mc.level) { history.resetTracking(); world = mc.level; sampleTicks = 0; }
        if (sampleTicks++ % 5 != 0) return;
        server = currentServer();
        dimension = mc.level.dimension().identifier().toString();
        long now = System.currentTimeMillis();
        Set<UUID> online = new HashSet<>();
        for (var p : mc.getConnection().getOnlinePlayers()) online.add(p.getProfile().id());
        List<Sight> sightings = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player player) || player == mc.player || !player.isAlive()
                || player instanceof dev.monocle.client.utils.entity.fakeplayer.FakePlayerEntity) continue;
            StringBuilder equipment = new StringBuilder();
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                var stack = player.getItemBySlot(slot);
                if (stack.isEmpty()) continue;
                if (!equipment.isEmpty()) equipment.append(" · ");
                equipment.append(slot.getName()).append(": ").append(stack.getHoverName().getString());
                if (stack.isDamageableItem()) equipment.append(" (").append(stack.getMaxDamage() - stack.getDamageValue()).append("/").append(stack.getMaxDamage()).append(")");
            }
            sightings.add(new Sight(player.getUUID(), clean(player.getName().getString(), 64),
                player.getX(), player.getY(), player.getZ(), player.getBbWidth(), player.getBbHeight(),
                Math.round(player.getHealth() + player.getAbsorptionAmount()), Math.max(1, Math.round(player.getMaxHealth() + player.getAbsorptionAmount())),
                clean(equipment.toString(), 1024), now));
            if (sightings.size() >= 2000) break;
        }
        for (Entry entry : history.update(server, dimension, sightings, online, now, expiryHours.get() * 3_600_000L, historyLimit.get())) {
            if (notifyDepartures.get()) info("%s left the player list near %s (last observed position).", entry.sight().name(), coords(entry.sight()));
        }
        cachedEntries = history.scope(server, dimension);
    }

    private List<Entry> currentEntries() {
        return currentScope() ? cachedEntries : List.of();
    }

    private boolean marker(Entry entry) { return entry.kind() == Kind.LeftPlayerList || showLastSeen.get() && entry.kind() != Kind.Visible; }
    private static String label(Kind kind) {
        return switch (kind) {
            case Visible -> "Tracked";
            case LastSeen -> "Last seen";
            case LeftPlayerList -> "Left player list";
            case BackOnList -> "Back on list · last seen";
        };
    }
    private static String coords(Sight s) { return BlockPos.containing(s.x(), s.y(), s.z()).toShortString(); }
    private static String time(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    private static String clean(String text, int limit) { return dev.monocle.client.utils.render.NotificationFeed.plain(text, limit); }

    @EventHandler private void onRender3D(Render3DEvent event) {
        for (Entry entry : currentEntries()) {
            if (!marker(entry)) continue;
            Sight s = entry.sight();
            if (!PlayerUtils.isWithinCamera(s.x(), s.y(), s.z(), mc.options.renderDistance().get() * 16)) continue;
            double x = s.x() - s.width() / 2, z = s.z() - s.width() / 2;
            if (fullHeight.get()) event.renderer.box(x, s.y(), z, x + s.width(), s.y() + s.height(), z + s.width(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            else event.renderer.sideHorizontal(x, s.y(), z, x + s.width(), z + s.width(), sideColor.get(), lineColor.get(), shapeMode.get());
        }
    }

    @EventHandler private void onRender2D(Render2DEvent event) {
        for (Entry entry : currentEntries()) {
            if (!marker(entry)) continue;
            Sight s = entry.sight();
            if (!PlayerUtils.isWithinCamera(s.x(), s.y(), s.z(), mc.options.renderDistance().get() * 16)) continue;
            pos.set(s.x(), s.y() + s.height() + .5, s.z());
            if (!NametagUtils.to2D(pos, scale.get())) continue;
            String title = s.name() + " · " + label(entry.kind()) + " · " + Math.max(0, (System.currentTimeMillis() - s.seenAt()) / 1000) + "s ago";
            String health = " " + s.health() + " HP (last seen)";
            TextRenderer text = TextRenderer.get();
            NametagUtils.begin(pos);
            double width = (text.getWidth(title) + text.getWidth(health)) / 2;
            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(-width, 0, width * 2, text.getHeight(), nameBackgroundColor.get());
            Renderer2D.COLOR.render();
            text.beginBig(event.graphics);
            double x = text.render(title, -width, 0, nameColor.get());
            double fraction = s.health() / (double) Math.max(1, s.maxHealth());
            text.render(health, x, 0, fraction <= .333 ? RED : fraction <= .666 ? ORANGE : GREEN);
            text.end();
            NametagUtils.end();
        }
    }

    @Override public String getInfoString() { return Integer.toString(currentEntries().size()); }

    @Override public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        list.add(theme.label("Player-list departure is evidence, not proof of logout. Equipment and health are last observed. History is saved locally.", 480));
        list.add(theme.button("Open Encounter History")).widget().action = () -> mc.gui.setScreen(new WindowScreen(theme, "Encounter History") {
            private int page;
            private String filter = "";
            @Override public void initWidgets() {
                if (mc.level == null) { add(theme.label("Join a world to view its encounter history.")); return; }
                String openedServer = currentServer(), openedDimension = mc.level.dimension().identifier().toString();
                history.prune(System.currentTimeMillis(), expiryHours.get() * 3_600_000L, historyLimit.get());
                add(theme.label(openedServer + " · " + openedDimension, 520));
                var search = add(theme.textBox(filter, "Filter player name")).widget();
                add(theme.button("Search")).widget().action = () -> { filter = search.get(); page = 0; reload(); };
                var entries = history.scope(openedServer, openedDimension).stream()
                    .filter(e -> e.sight().name().toLowerCase(java.util.Locale.ROOT).contains(filter.toLowerCase(java.util.Locale.ROOT))).toList();
                page = Math.clamp(page, 0, Math.max(0, (entries.size() - 1) / 20));
                add(theme.label(entries.size() + " sightings · page " + (page + 1) + " (20 per page)"));
                for (Entry entry : entries.subList(page * 20, Math.min(entries.size(), page * 20 + 20))) {
                    Sight s = entry.sight();
                    add(theme.label(s.name() + " · " + label(entry.kind()) + "\n" + coords(s) + " · Seen " + time(s.seenAt()) + "\n" + s.health() + " HP · " + (s.equipment().isEmpty() ? "No visible equipment" : s.equipment()), 520));
                    var buttons = add(theme.horizontalList()).widget();
                    buttons.add(theme.button("Copy coordinates")).widget().action = () -> mc.keyboardHandler.setClipboard(coords(s));
                    var waypoint = buttons.add(theme.button("Waypoint")).widget();
                    waypoint.action = () -> {
                        if (mc.level == null || !openedServer.equals(currentServer()) || !openedDimension.equals(mc.level.dimension().identifier().toString())) return;
                        Dimension dim = java.util.Arrays.stream(Dimension.values()).filter(d -> d.toString().equals(openedDimension)).findFirst().orElse(null);
                        if (dim == null) { warning("Custom dimension cannot be represented by the waypoint system. Copy coordinates instead."); return; }
                        Waypoint point = new Waypoint.Builder().name(s.name() + " · last seen").icon("square").pos(BlockPos.containing(s.x(), s.y(), s.z())).dimension(dim).build();
                        point.opposite.set(false);
                        Waypoints.get().add(point);
                        waypoint.action = null;
                        waypoint.set("Saved");
                        info("Waypoint saved for %s's last observed position.", s.name());
                    };
                    buttons.add(theme.button("Forget")).widget().action = () -> { history.remove(entry.key()); reload(); };
                }
                var pages = add(theme.horizontalList()).widget();
                if (page > 0) pages.add(theme.button("Previous")).widget().action = () -> { page--; reload(); };
                if ((page + 1) * 20 < entries.size()) pages.add(theme.button("Next")).widget().action = () -> { page++; reload(); };
                add(theme.button("Refresh")).widget().action = this::reload;
                add(theme.button("Clear this server / dimension")).widget().action = () -> { history.clearScope(openedServer, openedDimension); reload(); };
            }
        });
        return list;
    }

    @Override public CompoundTag toTag() {
        CompoundTag tag = super.toTag();
        if (tag == null) return null;
        history.prune(System.currentTimeMillis(), expiryHours.get() * 3_600_000L, historyLimit.get());
        ListTag saved = new ListTag();
        for (Entry entry : history.entries()) saved.add(encode(entry));
        tag.put("encounter-history", saved);
        return tag;
    }

    public static CompoundTag encode(Entry entry) {
        Sight s = entry.sight();
        CompoundTag tag = new CompoundTag();
        tag.putString("server", entry.key().server()); tag.putString("dimension", entry.key().dimension());
        tag.putString("uuid", s.uuid().toString()); tag.putString("name", s.name()); tag.putString("equipment", s.equipment());
        tag.putString("kind", (entry.kind() == Kind.Visible ? Kind.LastSeen : entry.kind()).name());
        tag.putDouble("x", s.x()); tag.putDouble("y", s.y()); tag.putDouble("z", s.z());
        tag.putDouble("width", s.width()); tag.putDouble("height", s.height());
        tag.putInt("health", s.health()); tag.putInt("max-health", s.maxHealth()); tag.putLong("seen", s.seenAt());
        return tag;
    }

    public static Entry decode(CompoundTag tag) {
        String server = tag.getStringOr("server", ""), dim = tag.getStringOr("dimension", "");
        UUID id = UUID.fromString(tag.getStringOr("uuid", ""));
        double x = tag.getDoubleOr("x", Double.NaN), y = tag.getDoubleOr("y", Double.NaN), z = tag.getDoubleOr("z", Double.NaN);
        double width = tag.getDoubleOr("width", .6), height = tag.getDoubleOr("height", 1.8);
        if (server.isBlank() || server.length() > 512 || dim.isBlank() || dim.length() > 256 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 30_000_000
            || !Double.isFinite(width) || width <= 0 || width > 16 || !Double.isFinite(height) || height <= 0 || height > 32) throw new IllegalArgumentException("Invalid sighting");
        long seen = tag.getLongOr("seen", 0);
        if (seen <= 0 || seen > System.currentTimeMillis() + 300_000) throw new IllegalArgumentException("Invalid timestamp");
        Sight sight = new Sight(id, clean(tag.getStringOr("name", "Unknown"), 64), x, y, z, width, height,
            Math.clamp(tag.getIntOr("health", 0), 0, 10000), Math.clamp(tag.getIntOr("max-health", 20), 1, 10000),
            clean(tag.getStringOr("equipment", ""), 1024), seen);
        return new Entry(new Key(server, dim, id), sight, Kind.valueOf(tag.getStringOr("kind", "LastSeen")));
    }

    @Override public Module fromTag(CompoundTag tag) {
        super.fromTag(tag);
        history.clear();
        var saved = tag.getListOrEmpty("encounter-history");
        for (int i = 0; i < Math.min(saved.size(), 2000); i++) {
            if (!(saved.get(i) instanceof CompoundTag item)) continue;
            try { history.restore(decode(item)); } catch (IllegalArgumentException ignored) { }
        }
        history.prune(System.currentTimeMillis(), expiryHours.get() * 3_600_000L, historyLimit.get());
        return this;
    }
}
