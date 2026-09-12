/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.misc;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.world.ServerConnectBeginEvent;
import dev.monocle.client.events.game.GameJoinedEvent;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.settings.IntSetting;
import dev.monocle.client.settings.StringListSetting;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.DoubleSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.List;
import java.util.Locale;

public class AutoReconnect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> time = sgGeneral.add(new DoubleSetting.Builder()
        .name("delay")
        .description("The amount of seconds to wait before reconnecting to the server.")
        .defaultValue(3.5)
        .range(1, 600)
        .decimalPlaces(1)
        .build()
    );

    public final Setting<Boolean> button = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-buttons")
        .description("Will hide the buttons related to Auto Reconnect.")
        .defaultValue(false)
        .build()
    );

    public Pair<ServerAddress, ServerData> lastServerConnection;

    private final Setting<Boolean> adaptive = sgGeneral.add(new BoolSetting.Builder()
        .name("adaptive-delay").description("Double the delay after each retry, up to the maximum.")
        .defaultValue(true).build());
    private final Setting<Double> maxDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("maximum-delay").description("Maximum adaptive delay in seconds; never less than the initial delay.")
        .defaultValue(30).range(1, 600).visible(adaptive::get).build());
    private final Setting<Integer> maxAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("maximum-attempts").description("Automatic retries before stopping. Zero means unlimited.")
        .defaultValue(0).range(0, 10000).sliderRange(0, 100).build());
    private final Setting<Double> stableTime = sgGeneral.add(new DoubleSetting.Builder()
        .name("stable-connection-time").description("Seconds connected before clearing retry history.")
        .defaultValue(30).range(1, 600).build());
    private final Setting<Boolean> stopOnErrors = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-on-permanent-errors").description("Stop for recognized vanilla bans, invalid sessions, version errors and duplicate logins.")
        .defaultValue(true).build());
    private final Setting<List<String>> stopReasons = sgGeneral.add(new StringListSetting.Builder()
        .name("stop-reasons").description("Do not retry reasons containing any of these phrases, ignoring case. Empty entries are ignored.")
        .defaultValue(List.of()).build());

    private int attempts;
    private long joinedAt;
    private boolean retryConnecting;

    public int attempts() { return attempts; }

    public double retryDelay() { return retryDelay(time.get(), maxDelay.get(), attempts, adaptive.get()); }

    public static double retryDelay(double base, double cap, int attempts, boolean adaptive) {
        // A zero-delay legacy setting must not create a tight reconnect loop.
        base = Double.isFinite(base) ? Math.clamp(base, 1, 600) : 3.5;
        cap = Double.isFinite(cap) ? Math.clamp(cap, base, 600) : base;
        return adaptive ? Math.min(cap, Math.scalb(base, Math.clamp(attempts, 0, 30))) : base;
    }

    public String stopReason(Component reason) {
        if (stopOnErrors.get() && permanentError(reason)) return "Check disconnect reason";
        if (matchesReason(reason.getString(), stopReasons.get())) return "Blocked by reason filter";
        if (maxAttempts.get() > 0 && attempts >= maxAttempts.get()) return "Retry limit reached";
        return "";
    }

    public static boolean matchesReason(String reason, List<String> filters) {
        String lower = reason.toLowerCase(Locale.ROOT);
        return filters.stream().map(String::strip).filter(s -> !s.isEmpty())
            .anyMatch(s -> lower.contains(s.toLowerCase(Locale.ROOT)));
    }

    public static boolean permanentError(Component reason) {
        if (reason.getContents() instanceof TranslatableContents text) {
            String key = text.getKey();
            if (key.startsWith("multiplayer.disconnect.banned") || switch (key) {
                case "disconnect.loginFailedInfo.invalidSession", "disconnect.loginFailedInfo.userBanned",
                    "disconnect.loginFailedInfo.insufficientPrivileges", "multiplayer.disconnect.incompatible",
                    "multiplayer.disconnect.outdated_client", "multiplayer.disconnect.outdated_server",
                    "multiplayer.disconnect.duplicate_login", "multiplayer.disconnect.not_whitelisted" -> true;
                default -> false;
            }) return true;
            for (Object arg : text.getArgs()) if (arg instanceof Component c && permanentError(c)) return true;
        }
        return reason.getSiblings().stream().anyMatch(AutoReconnect::permanentError);
    }

    public void reconnect(boolean manual) {
        if (lastServerConnection == null) return;
        if (manual) attempts = 0;
        else if (attempts < Integer.MAX_VALUE) attempts++;
        retryConnecting = true;
        try {
            ConnectScreen.startConnecting(new TitleScreen(), MonocleClient.mc,
                lastServerConnection.left(), lastServerConnection.right(), false, null);
        } finally {
            retryConnecting = false;
        }
    }

    public AutoReconnect() {
        super(Categories.Misc, "auto-reconnect", "Automatically reconnects when disconnected from a server.");
        MonocleClient.EVENT_BUS.subscribe(new StaticListener());
    }

    private class StaticListener {
        @EventHandler
        private void onGameJoined(ServerConnectBeginEvent event) {
            if (!retryConnecting) attempts = 0;
            joinedAt = 0;
            lastServerConnection = new ObjectObjectImmutablePair<>(event.address, event.info);
        }

        @EventHandler
        private void onJoined(GameJoinedEvent event) {
            if (MonocleClient.mc.isLocalServer()) {
                lastServerConnection = null;
                attempts = 0;
                joinedAt = 0;
            } else joinedAt = System.nanoTime();
        }

        @EventHandler
        private void onLeft(GameLeftEvent event) {
            if (joinedAt != 0 && System.nanoTime() - joinedAt >= stableTime.get() * 1_000_000_000L) attempts = 0;
            joinedAt = 0;
        }
    }
}
