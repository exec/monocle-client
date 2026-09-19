/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.monocle.client.mixininterface.IVec3;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.Chams;
import dev.monocle.client.systems.modules.player.Derp;
import dev.monocle.client.systems.modules.world.SchematicSelector;
import dev.monocle.client.utils.player.Rotations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin
    extends LivingEntityRenderer<Avatar, AvatarRenderState, PlayerModel> {
    // Chams

    @Unique
    private Chams chams;

    public AvatarRendererMixin(EntityRendererProvider.Context ctx, PlayerModel model, float shadowRadius) {
        super(ctx, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init$chams(CallbackInfo ci) {
        chams = Modules.get().get(Chams.class);
    }

    // Chams - Player scale

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("RETURN"))
    private void updateRenderState$scale(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        if (!chams.isActive() || !chams.players.get()) return;
        if (chams.ignoreSelf.get() && entity == mc.player) return;

        float v = chams.playersScale.get().floatValue();
        state.scale *= v;

        if (state.nameTagAttachment != null)
            ((IVec3) state.nameTagAttachment).monocle$setY(state.nameTagAttachment.y + (entity.getBbHeight() * v - entity.getBbHeight()));
    }

    // Chams - Hand Texture

    @ModifyExpressionValue(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entityTranslucent(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
    private RenderType renderArm$texture(RenderType original, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, Identifier skinTexture, ModelPart arm, boolean hasSleeve) {
        if (chams.isActive() && chams.hand.get()) {
            Identifier texture = chams.handTexture.get() ? skinTexture : Chams.BLANK;
            return RenderTypes.entityTranslucent(texture);
        }

        return original;
    }

    // Chams - Hand Color

    @WrapWithCondition(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"))
    private boolean renderArm$color(SubmitNodeCollector instance, ModelPart modelPart, PoseStack matrixStack, RenderType renderLayer, int light, int uv, TextureAtlasSprite sprite) {
        if (chams.isActive() && chams.hand.get()) {
            instance.submitModelPart(modelPart, matrixStack, renderLayer, light, uv, null, chams.handColor.get().getPacked(), null);
            return false;
        }

        return true;
    }

    // Rotations

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("RETURN"))
    private void extractRenderState$rotations(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        Derp derp = Modules.get().get(Derp.class);
        if (derp.isActive() && entity == mc.player) {
            state.yRot = 0;
            state.bodyRot = derp.visualYaw();
            state.xRot = derp.visualPitch();
            return;
        }
        if (Rotations.rotating && entity == mc.player) {
            state.yRot = 0;
            state.bodyRot = Rotations.serverYaw;
            state.xRot = Rotations.serverPitch;
        }
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("RETURN"))
    private void monocle$selectionWandThirdPerson(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        if (entity != mc.player || !SchematicSelector.isHoldingWand()) return;
        ItemStack wand = SchematicSelector.renderMainHand(entity.getMainHandItem());
        boolean right = entity.getMainArm() == HumanoidArm.RIGHT;
        mc.getItemModelResolver().updateForLiving(state.getMainHandItemState(), wand,
            right ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND, entity);
        if (right) {
            state.rightHandItemStack = wand.copy();
            state.rightArmPose = HumanoidModel.ArmPose.ITEM;
        } else {
            state.leftHandItemStack = wand.copy();
            state.leftArmPose = HumanoidModel.ArmPose.ITEM;
        }
        state.swingAnimationType = wand.getSwingAnimation().type();
    }
}
