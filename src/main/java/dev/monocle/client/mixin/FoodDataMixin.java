/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.NoSlow;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @ModifyExpressionValue(method = "hasEnoughFood()Z", at = @At(value = "CONSTANT", args = "floatValue=6.0f"))
    private float onHunger(float constant) {
        if (Modules.get().get(NoSlow.class).hunger()) return -1;
        return constant;
    }
}
