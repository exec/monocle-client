/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.ServerSpoof;
import net.minecraft.client.resources.server.DownloadedPackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {
    @Inject(method = "onReloadSuccess", at = @At("TAIL"))
    private void removeInactivePacksTail(CallbackInfo ci) {
        Modules.get().get(ServerSpoof.class).silentAcceptResourcePack = false;
    }
}
