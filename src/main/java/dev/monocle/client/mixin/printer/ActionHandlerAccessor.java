package dev.monocle.client.mixin.printer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.ActionHandler", remap = false)
public interface ActionHandlerAccessor {
    @Accessor("actionQueue") Queue<?> monocle$getActionQueue();
}
