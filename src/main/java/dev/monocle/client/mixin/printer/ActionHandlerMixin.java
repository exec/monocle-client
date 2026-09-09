package dev.monocle.client.mixin.printer;

import dev.monocle.client.modintegration.PrinterIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.ActionHandler", remap = false)
public abstract class ActionHandlerMixin {
    @Inject(method = "onGameTick", at = @At("HEAD"), cancellable = true)
    private void monocle$guardQueuedJobs(CallbackInfo ci) {
        if (!PrinterIntegration.beforeActionTick(this)) ci.cancel();
    }

    @Inject(method = "onGameTick", at = @At("TAIL"))
    private void monocle$finishPause(CallbackInfo ci) {
        PrinterIntegration.afterActionTick(this);
    }
}
