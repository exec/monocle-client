/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

import com.mojang.blaze3d.systems.RenderPassBackend;

public interface IGpuDevice {
    /**
     * Currently there can only be a single scissor pushed at once.
     */
    void monocle$pushScissor(int x, int y, int width, int height);

    void monocle$popScissor();

    /**
     * This is an *INTERNAL* method, it shouldn't be called.
     */
    @Deprecated
    @SuppressWarnings("DeprecatedIsStillUsed")
    void monocle$onCreateRenderPass(RenderPassBackend backend);
}
