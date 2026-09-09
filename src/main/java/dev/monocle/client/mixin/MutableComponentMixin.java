/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.mixininterface.IComponent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MutableComponent.class)
public abstract class MutableComponentMixin implements IComponent {
    @Shadow
    private @Nullable Language decomposedWith;

    @Override
    public void monocle$invalidateCache() {
        this.decomposedWith = null;
    }
}
