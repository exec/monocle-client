/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@NullMarked
@Mixin(AddressCheck.class)
public interface AddressCheckMixin {
    @Inject(method = "createFromService", at = @At("HEAD"), cancellable = true)
    private static void onCreateFromService(CallbackInfoReturnable<AddressCheck> cir) {
        cir.setReturnValue(new AddressCheck() {
            @Override
            public boolean isAllowed(ResolvedServerAddress address) {
                return true;
            }

            @Override
            public boolean isAllowed(ServerAddress address) {
                return true;
            }
        });
    }
}
