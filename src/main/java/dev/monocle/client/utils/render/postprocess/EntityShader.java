package dev.monocle.client.utils.render.postprocess;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.monocle.client.mixininterface.ILevelRenderer;
import net.minecraft.world.entity.Entity;

import static dev.monocle.client.MonocleClient.mc;

public abstract class EntityShader extends PostProcessShader {
    protected EntityShader(RenderPipeline pipeline) {
        super(pipeline);
    }

    public abstract boolean shouldDraw(Entity entity);

    @Override
    protected void preDraw() {
        ((ILevelRenderer) mc.levelRenderer).monocle$pushEntityOutlineFramebuffer(framebuffer);
    }

    @Override
    protected void postDraw() {
        ((ILevelRenderer) mc.levelRenderer).monocle$popEntityOutlineFramebuffer();
    }

    public void submitVertices() {
    }
}
