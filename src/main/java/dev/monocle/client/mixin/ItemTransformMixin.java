/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.render.ApplyTransformationEvent;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemTransform.class)
public abstract class ItemTransformMixin {
    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void onApply(boolean applyLeftHandFix, PoseStack.Pose pose, CallbackInfo ci) {
        ApplyTransformationEvent event = MonocleClient.EVENT_BUS.post(ApplyTransformationEvent.get((ItemTransform) (Object) this, applyLeftHandFix));
        if (event.isCancelled()) ci.cancel();
    }
}
