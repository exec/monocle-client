/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderPassBackend;
import dev.monocle.client.mixininterface.IGpuDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GpuDevice.class)
public abstract class GpuDeviceMixin implements IGpuDevice {
    @Shadow
    @Final
    private GpuDeviceBackend backend;

    @Override
    public void monocle$pushScissor(int x, int y, int width, int height) {
        ((IGpuDevice) backend).monocle$pushScissor(x, y, width, height);
    }

    @Override
    public void monocle$popScissor() {
        ((IGpuDevice) backend).monocle$popScissor();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void monocle$onCreateRenderPass(RenderPassBackend backend) {
        ((IGpuDevice) this.backend).monocle$onCreateRenderPass(backend);
    }
}
