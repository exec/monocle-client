package dev.monocle.client.mixin.printer;

import me.aleksilassila.litematica.printer.SchematicBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.guides.Guide", remap = false)
public interface GuideAccessor {
    @Accessor("state") SchematicBlockState monocle$getState();
}
