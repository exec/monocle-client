/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.render.ArmRenderEvent;
import dev.monocle.client.events.render.HeldItemRendererEvent;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.HandView;
import dev.monocle.client.systems.modules.world.SchematicSelector;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    // Substitute render-call values only: the renderer cache and real inventory are never replaced.
    @ModifyExpressionValue(method = "submitHandsWithItems", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;mainHandItem:Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack monocle$selectionWandHand(ItemStack original) {
        return SchematicSelector.renderMainHand(original);
    }

    @ModifyExpressionValue(method = "evaluateWhichHandsToRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack monocle$selectionWandHandChoice(ItemStack original) {
        return SchematicSelector.renderMainHand(original);
    }

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float offHandHeight;

    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private ItemStack offHandItem;

    @Shadow
    protected abstract boolean shouldInstantlyReplaceVisibleItem(ItemStack currentlyVisibleItem, ItemStack expectedItem);

    @ModifyExpressionValue(method = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getAttackAnim(F)F"))
    private float modifySwing(float attackValue) {
        HandView module = Modules.get().get(HandView.class);
        InteractionHand hand = Objects.requireNonNullElse(mc.player.swingingArm, InteractionHand.MAIN_HAND);

        if (module.isActive()) {
            if (module.swordSlash() && hand == InteractionHand.MAIN_HAND && mainHandItem.is(ItemTags.SWORDS)) {
                return 0f;
            }
            if (hand == InteractionHand.OFF_HAND && !offHandItem.isEmpty()) {
                return attackValue + module.offSwing.get().floatValue();
            }
            if (hand == InteractionHand.MAIN_HAND && !mainHandItem.isEmpty()) {
                return attackValue + module.mainSwing.get().floatValue();
            }
        }

        return attackValue;
    }

    @ModifyReturnValue(method = "shouldInstantlyReplaceVisibleItem", at = @At("RETURN"))
    private boolean modifySkipSwapAnimation(boolean original) {
        return original || Modules.get().get(HandView.class).skipSwapping();
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F", ordinal = 2), index = 0)
    private float modifyEquipProgressMainhand(float value) {
        HandView handView = Modules.get().get(HandView.class);
        if (handView.swordSlash() && mc.player.getMainHandItem().is(ItemTags.SWORDS)) return value;

        float f = mc.player.getItemSwapScale(1f);
        float modified = handView.oldAnimations() ? 1 : f * f * f;

        return (shouldInstantlyReplaceVisibleItem(mainHandItem, mc.player.getMainHandItem()) ? modified : 0) - mainHandHeight;
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F", ordinal = 3), index = 0)
    private float modifyEquipProgressOffhand(float value) {
        return (shouldInstantlyReplaceVisibleItem(offHandItem, mc.player.getOffhandItem()) ? 1 : 0) - offHandHeight;
    }

    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V", shift = At.Shift.BEFORE))
    private void onRenderItem(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
        MonocleClient.EVENT_BUS.post(HeldItemRendererEvent.get(hand, poseStack));
    }

    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderPlayerArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFFLnet/minecraft/world/entity/HumanoidArm;)V"))
    private void onRenderArm(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
        MonocleClient.EVENT_BUS.post(ArmRenderEvent.get(hand, poseStack));
    }

    @Inject(method = "applyEatTransform", at = @At(value = "INVOKE", target = "Ljava/lang/Math;pow(DD)D", shift = At.Shift.BEFORE), cancellable = true)
    private void cancelTransformations(PoseStack poseStack, float frameInterp, HumanoidArm arm, ItemStack itemStack, Player player, CallbackInfo ci) {
        if (Modules.get().get(HandView.class).disableFoodAnimation()) ci.cancel();
    }
}
