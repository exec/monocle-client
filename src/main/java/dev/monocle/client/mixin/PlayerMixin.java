/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.entity.DropItemsEvent;
import dev.monocle.client.events.entity.player.ClipAtLedgeEvent;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.Flight;
import dev.monocle.client.systems.modules.movement.NoSlow;
import dev.monocle.client.systems.modules.movement.Sprint;
import dev.monocle.client.systems.modules.player.AirMine;
import dev.monocle.client.systems.modules.player.Reach;
import dev.monocle.client.systems.modules.player.SpeedMine;
import dev.monocle.client.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity {
    @Shadow
    public abstract Abilities getAbilities();

    protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level world) {
        super(entityType, world);
    }

    @Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true)
    protected void clipAtLedge(CallbackInfoReturnable<Boolean> cir) {
        if (!level().isClientSide()) return;

        ClipAtLedgeEvent event = MonocleClient.EVENT_BUS.post(ClipAtLedgeEvent.get());
        if (event.isSet()) cir.setReturnValue(event.isClip());
    }

    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void onDropItem(ItemStack itemStack, boolean thrownFromHand, CallbackInfoReturnable<ItemEntity> cir) {
        if (level().isClientSide() && !itemStack.isEmpty()) {
            if (MonocleClient.EVENT_BUS.post(DropItemsEvent.get(itemStack)).isCancelled()) cir.setReturnValue(null);
        }
    }

    @Inject(method = "isSpectator", at = @At("HEAD"), cancellable = true)
    private void onIsSpectator(CallbackInfoReturnable<Boolean> cir) {
        if (mc.getConnection() == null) cir.setReturnValue(false);
    }

    @Inject(method = "isCreative", at = @At("HEAD"), cancellable = true)
    private void onIsCreative(CallbackInfoReturnable<Boolean> cir) {
        if (mc.getConnection() == null) cir.setReturnValue(false);
    }

    @ModifyReturnValue(method = "getDestroySpeed", at = @At(value = "RETURN"))
    public float onGetBlockBreakingSpeed(float breakSpeed, BlockState state) {
        if (!level().isClientSide()) return breakSpeed;

        SpeedMine speedMine = Modules.get().get(SpeedMine.class);
        AirMine airMine = Modules.get().get(AirMine.class);
        if (airMine.isActive() && airMine.liftsAirPenalty() && (Player) (Object) this == mc.player) breakSpeed *= 5.0f;

        if (!speedMine.isActive() || speedMine.mode.get() != SpeedMine.Mode.Normal || !speedMine.filter(state.getBlock())) return breakSpeed;

        float breakSpeedMod = (float) (breakSpeed * speedMine.modifier.get());

        if (mc.hitResult instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            if (speedMine.modifier.get() < 1 || (BlockUtils.canInstaBreak(pos, breakSpeed) == BlockUtils.canInstaBreak(pos, breakSpeedMod))) {
                return breakSpeedMod;
            } else {
                return 0.9f / BlockUtils.calcBlockBreakingDelta2(pos, 1);
            }
        }

        return breakSpeed;
    }

    @ModifyReturnValue(method = "getSpeed", at = @At("RETURN"))
    private float onGetSpeed(float original) {
        if (!level().isClientSide()) return original;
        if (!Modules.get().get(NoSlow.class).slowness()) return original;

        float walkSpeed = getAbilities().getWalkingSpeed();

        if (original < walkSpeed) {
            if (isSprinting()) return (float) (walkSpeed * 1.30000001192092896);
            else return walkSpeed;
        }

        return original;
    }

    @Inject(method = "getFlyingSpeed", at = @At("HEAD"), cancellable = true)
    private void onGetFlyingSpeed(CallbackInfoReturnable<Float> cir) {
        if (!level().isClientSide()) return;

        float speed = Modules.get().get(Flight.class).getFlyingSpeed();
        if (speed != -1) cir.setReturnValue(speed);
    }

    @WrapWithCondition(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private boolean keepSprint$setDeltaMovement(Player instance, Vec3 vec3d) {
        return Modules.get().get(Sprint.class).stopSprinting();
    }

    @WrapWithCondition(method = "causeExtraKnockback", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setSprinting(Z)V"))
    private boolean keepSprint$setSprinting(Player instance, boolean b) {
        return Modules.get().get(Sprint.class).stopSprinting();
    }

    @ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
    private double modifyBlockInteractionRange(double original) {
        return Math.max(0, original + Modules.get().get(Reach.class).blockReach());
    }

    @ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
    private double modifyEntityInteractionRange(double original) {
        return Math.max(0, original + Modules.get().get(Reach.class).entityReach());
    }
}
