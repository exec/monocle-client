/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.InputConstants;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.monocle.MouseClickEvent;
import dev.monocle.client.events.monocle.MouseScrollEvent;
import dev.monocle.client.systems.modules.world.SchematicSelector;
import dev.monocle.client.utils.misc.input.Input;
import dev.monocle.client.utils.misc.input.KeyAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.mojang.blaze3d.platform.InputConstants.RELEASE;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    public abstract double getScaledXPos(Window window);

    @Shadow
    public abstract double getScaledYPos(Window window);

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long handle, MouseButtonInfo rawButtonInfo, int action, CallbackInfo ci) {
        Input.setButtonState(rawButtonInfo.button(), action != RELEASE);

        if (SchematicSelector.handleWandInput(InputConstants.Type.MOUSE.getOrCreate(rawButtonInfo.button()), action != RELEASE)) {
            ci.cancel();
            return;
        }

        MouseButtonEvent click = new MouseButtonEvent(getScaledXPos(minecraft.getWindow()), getScaledYPos(minecraft.getWindow()), rawButtonInfo);
        if (MonocleClient.EVENT_BUS.post(MouseClickEvent.get(click, KeyAction.get(action))).isCancelled()) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        if (MonocleClient.EVENT_BUS.post(MouseScrollEvent.get(yoffset)).isCancelled()) ci.cancel();
    }
}
