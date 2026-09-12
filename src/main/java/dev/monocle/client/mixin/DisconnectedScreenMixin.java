/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.AutoReconnect;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
    @Shadow
    @Final
    private LinearLayout layout;
    @Shadow @Final private DisconnectionDetails details;
    @Unique
    private Button reconnectBtn;
    @Unique private Button pauseBtn;
    @Unique private StringWidget retryStatus;
    @Unique private long deadline;
    @Unique private long remaining;
    @Unique private boolean initialized;
    @Unique private boolean paused;
    @Unique private boolean cancelled;
    @Unique private boolean connecting;

    protected DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;arrangeElements()V", shift = At.Shift.BEFORE))
    private void addButtons(CallbackInfo ci) {
        AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);

        if (!initialized) {
            initialized = true;
            remaining = (long) (autoReconnect.retryDelay() * 1_000_000_000L);
            deadline = System.nanoTime() + remaining;
        }

        if (autoReconnect.lastServerConnection != null && !autoReconnect.button.get()) {
            String server = autoReconnect.lastServerConnection.right().name;
            layout.addChild(new StringWidget(Component.literal("Reconnect to " + server), font).setMaxWidth(300));
            retryStatus = layout.addChild(new StringWidget(300, 12, Component.empty(), font));
            reconnectBtn = new Button.Builder(Component.literal("Reconnect Now"), _ -> tryConnecting(true)).build();
            layout.addChild(reconnectBtn);
            pauseBtn = layout.addChild(new Button.Builder(Component.literal("Pause Retries"), _ -> {
                if (!autoReconnect.isActive() || cancelled) {
                    autoReconnect.enable();
                    cancelled = paused = false;
                    deadline = System.nanoTime() + (long) (autoReconnect.retryDelay() * 1_000_000_000L);
                } else if (paused) {
                    paused = false;
                    deadline = System.nanoTime() + remaining;
                } else {
                    paused = true;
                    remaining = Math.max(0, deadline - System.nanoTime());
                }
                updateStatus();
            }).build());
            layout.addChild(new Button.Builder(Component.literal("Cancel Retries"), _ -> {
                cancelled = true;
                autoReconnect.disable();
                updateStatus();
            }).build());
            updateStatus();
        }
    }

    @Override
    public void tick() {
        AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
        if (mc.gui.screen() != (Object) this || connecting) return;
        updateStatus();
        if (!autoReconnect.isActive() || autoReconnect.lastServerConnection == null || paused || cancelled
            || !autoReconnect.stopReason(details.reason()).isEmpty()) return;
        if (System.nanoTime() >= deadline) tryConnecting(false);
    }

    @Unique
    private void updateStatus() {
        if (retryStatus == null) return;
        AutoReconnect reconnect = Modules.get().get(AutoReconnect.class);
        String stop = reconnect.stopReason(details.reason());
        String status = cancelled ? "Cancelled" : !reconnect.isActive() ? "Auto Reconnect off"
            : !stop.isEmpty() ? stop : paused ? "Paused"
            : String.format(java.util.Locale.ROOT, "Retry in %.1fs", Math.max(0, deadline - System.nanoTime()) / 1_000_000_000.0);
        retryStatus.setMessage(Component.literal(status + " · Attempts: " + reconnect.attempts()));
        pauseBtn.setMessage(Component.literal(!reconnect.isActive() || cancelled ? "Enable Retries" : paused ? "Resume Retries" : "Pause Retries"));
        pauseBtn.active = stop.isEmpty();
    }

    @Unique
    private void tryConnecting(boolean manual) {
        if (connecting || mc.gui.screen() != (Object) this) return;
        connecting = true;
        Modules.get().get(AutoReconnect.class).reconnect(manual);
    }
}
