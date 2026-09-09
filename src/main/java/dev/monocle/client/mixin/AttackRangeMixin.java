/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.Hitboxes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.ToDoubleFunction;

@Mixin(AttackRange.class)
public abstract class AttackRangeMixin {
    @ModifyExpressionValue(method = "isInRange(Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/ToDoubleFunction;D)Z", at = @At(value = "FIELD", target = "Lnet/minecraft/world/item/component/AttackRange;hitboxMargin:F", opcode = Opcodes.GETFIELD))
    private float modifyHitboxMargin(float original, LivingEntity attacker, ToDoubleFunction<Vec3> distanceFunction, double extraBuffer) {
        float v = (float) Modules.get().get(Hitboxes.class).getEntityValue(attacker);
        return original + v;
    }
}
