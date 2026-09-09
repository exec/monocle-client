/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.monocle.client.mixininterface.IGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin implements IGpuDevice {
    @Unique
    private int x, y, width, height;

    @Unique
    private boolean set;

    @Override
    public void monocle$pushScissor(int x, int y, int width, int height) {
        if (set) throw new IllegalStateException("Currently there can only be one global scissor pushed");

        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        set = true;
    }

    @Override
    public void monocle$popScissor() {
        if (!set) throw new IllegalStateException("No scissor pushed");

        set = false;
    }

    @Deprecated
    @Override
    public void monocle$onCreateRenderPass(RenderPassBackend backend) {
        if (set) {
            backend.enableScissor(x, y, width, height);
        }
    }
}
