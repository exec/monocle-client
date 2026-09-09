/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.render.GetFovEvent;
import dev.monocle.client.mixininterface.ICamera;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.CameraTweaks;
import dev.monocle.client.systems.modules.render.FreeLook;
import dev.monocle.client.systems.modules.render.Freecam;
import dev.monocle.client.systems.modules.render.NoRender;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Camera.class)
public abstract class CameraMixin implements ICamera {
    @Shadow
    private boolean detached;

    @Shadow
    private float yRot;
    @Shadow
    private float xRot;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void getSubmergedFluidState(CallbackInfoReturnable<FogType> cir) {
        if (Modules.get().get(NoRender.class).noLiquidOverlay()) cir.setReturnValue(FogType.NONE);
    }

    @ModifyVariable(method = "getMaxZoom", at = @At("HEAD"), argsOnly = true, name = "cameraDist")
    private float modifyGetMaxZoom(float cameraDist) {
        if (Modules.get().get(Freecam.class).isActive()) return 0;

        CameraTweaks cameraTweaks = Modules.get().get(CameraTweaks.class);
        return cameraTweaks.isActive() ? (float) cameraTweaks.distance : cameraDist;
    }

    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void onGetMaxZoom(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (Modules.get().get(CameraTweaks.class).clip()) {
            cir.setReturnValue(cameraDist);
        }
    }

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void onAlignWithEntityTail(float partialTicks, CallbackInfo ci) {
        if (Modules.get().isActive(Freecam.class)) {
            this.detached = true;
        }
    }

    @ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void onAlignSetPosArgs(Args args, @Local(argsOnly = true, name = "partialTicks") float partialTicks) {
        Freecam freecam = Modules.get().get(Freecam.class);

        if (freecam.isActive()) {
            args.set(0, freecam.getX(partialTicks));
            args.set(1, freecam.getY(partialTicks));
            args.set(2, freecam.getZ(partialTicks));
        }
    }

    @ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void onAlignSetRotationArgs(Args args, @Local(argsOnly = true, name = "partialTicks") float partialTicks) {
        Freecam freecam = Modules.get().get(Freecam.class);
        FreeLook freeLook = Modules.get().get(FreeLook.class);

        if (freecam.isActive()) {
            args.set(0, (float) freecam.getYaw(partialTicks));
            args.set(1, (float) freecam.getPitch(partialTicks));
        } else if (Modules.get().get(HighwayBuilder.class).controlsPlayer()) {
            args.set(0, yRot);
            args.set(1, xRot);
        } else if (freeLook.isActive()) {
            args.set(0, freeLook.cameraYaw);
            args.set(1, freeLook.cameraPitch);
        }
    }

    /**
     * Set as spectator to disable smart culling
     */
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
    private boolean hookFreeCamDisableSmartCullInBlocks(boolean original) {
        return original || Modules.get().get(Freecam.class).isActive();
    }

    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float modifyFov(float original) {
        return MonocleClient.EVENT_BUS.post(GetFovEvent.get(original)).fov;
    }

    @Override
    public void monocle$setRot(double yaw, double pitch) {
        setRotation((float) yaw, (float) Mth.clamp(pitch, -90, 90));
    }
}
