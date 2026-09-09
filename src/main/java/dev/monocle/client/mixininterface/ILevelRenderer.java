/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

import com.mojang.blaze3d.pipeline.RenderTarget;

public interface ILevelRenderer {
    void monocle$pushEntityOutlineFramebuffer(RenderTarget framebuffer);

    void monocle$popEntityOutlineFramebuffer();
}
