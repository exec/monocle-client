/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.BetterTooltips;
import net.minecraft.world.item.component.TooltipDisplay;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TooltipDisplay.class)
public abstract class TooltipDisplayMixin {
    @ModifyExpressionValue(method = "shows", at = @At(value = "FIELD", target = "Lnet/minecraft/world/item/component/TooltipDisplay;hideTooltip:Z", opcode = Opcodes.GETFIELD))
    private boolean modifyHideTooltip(boolean original) {
        return original && !Modules.get().get(BetterTooltips.class).tooltip.get();
    }

    @ModifyExpressionValue(method = "shows", at = @At(value = "INVOKE", target = "Ljava/util/SequencedSet;contains(Ljava/lang/Object;)Z"))
    private boolean modifyHiddenComponents(boolean original) {
        return original && !Modules.get().get(BetterTooltips.class).additional.get();
    }
}
