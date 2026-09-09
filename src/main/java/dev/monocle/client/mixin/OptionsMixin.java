/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.game.ChangePerspectiveEvent;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.Freecam;
import dev.monocle.client.utils.misc.input.KeyBinds;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow
    @Final
    @Mutable
    public KeyMapping[] keyMappings;

    @Inject(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;keyMappings:[Lnet/minecraft/client/KeyMapping;", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void onInitAfterKeysAll(Minecraft minecraft, File workingDirectory, CallbackInfo ci) {
        keyMappings = KeyBinds.apply(keyMappings);
    }

    @Inject(method = "setCameraType", at = @At("HEAD"), cancellable = true)
    private void setPerspective(CameraType cameraType, CallbackInfo ci) {
        if (Modules.get() == null) return; // nothing is loaded yet, shouldersurfing compat

        ChangePerspectiveEvent event = MonocleClient.EVENT_BUS.post(ChangePerspectiveEvent.get(cameraType));

        if (event.isCancelled()) ci.cancel();

        if (Modules.get().isActive(Freecam.class)) ci.cancel();
    }
}
