/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.BetterChat;
import dev.monocle.client.systems.modules.render.Freecam;
import dev.monocle.client.systems.modules.render.NoRender;
import dev.monocle.client.systems.modules.world.SchematicSelector;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(Hud.class)
public abstract class HudMixin {
    @WrapOperation(method = "extractItemHotbar", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack monocle$selectionWandHotbar(Inventory inventory, int slot, Operation<ItemStack> original) {
        ItemStack stack = original.call(inventory, slot);
        return mc.player != null && inventory == mc.player.getInventory() ? SchematicSelector.renderWand(stack, slot) : stack;
    }

    @ModifyExpressionValue(method = "tick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack monocle$selectionWandName(ItemStack original) {
        return SchematicSelector.renderMainHand(original);
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void onExtractStatusEffectOverlay(CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noPotionIcons()) ci.cancel();
    }

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void onExtractPortalOverlay(GuiGraphicsExtractor graphics, float alpha, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noPortalOverlay()) ci.cancel();
    }

    @ModifyArgs(method = "extractCameraOverlays", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractTextureOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;F)V", ordinal = 0))
    private void onExtractPumpkinOverlay(Args args) {
        if (Modules.get().get(NoRender.class).noPumpkinOverlay()) args.set(2, 0f);
    }

    @ModifyArgs(method = "extractCameraOverlays", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractTextureOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;F)V", ordinal = 1))
    private void onExtractPowderedSnowOverlay(Args args) {
        if (Modules.get().get(NoRender.class).noPowderedSnowOverlay()) args.set(2, 0f);
    }

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void onExtractVignetteOverlay(GuiGraphicsExtractor graphics, Entity camera, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noVignette()) ci.cancel();
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void onExtractScoreboardSidebar(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noScoreboard()) ci.cancel();
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void onExtractScoreboardSidebar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noScoreboard()) ci.cancel();
    }

    @Inject(method = "extractSpyglassOverlay", at = @At("HEAD"), cancellable = true)
    private void onExtractSpyglassOverlay(GuiGraphicsExtractor graphics, float scale, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noSpyglassOverlay()) ci.cancel();
    }

    @ModifyExpressionValue(method = "extractCrosshair", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean alwaysRenderCrosshairInFreecam(boolean firstPerson) {
        return Modules.get().isActive(Freecam.class) || firstPerson;
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void onExtractCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noCrosshair()) ci.cancel();
    }

    @Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
    private void onExtractTitle(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noTitle()) ci.cancel();
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void onExtractHeldItemTooltip(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noHeldItemName()) ci.cancel();
    }

    @Inject(method = "onDisconnected", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V"), cancellable = true)
    private void onClear(CallbackInfo ci) {
        if (Modules.get().get(BetterChat.class).keepHistory()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true)
    private void onExtractNausea(GuiGraphicsExtractor graphics, float strength, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noNausea()) ci.cancel();
    }
}
