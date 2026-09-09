package dev.monocle.client.mixin.printer;

import dev.monocle.client.modintegration.PrinterIntegration;
import me.aleksilassila.litematica.printer.actions.Action;
import me.aleksilassila.litematica.printer.guides.Guide;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.Printer", remap = false)
public abstract class PrinterMixin {
    @Inject(method = "onGameTick", at = @At("HEAD"), cancellable = true)
    private void monocle$gateNewJobs(CallbackInfoReturnable<Boolean> cir) {
        if (!PrinterIntegration.allowNewJobs(this)) cir.setReturnValue(false);
    }

    @Inject(method = "getReachablePositions", at = @At("RETURN"), cancellable = true)
    private void monocle$limitScope(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (PrinterIntegration.ownsPrinter(this))
            cir.setReturnValue(cir.getReturnValue().stream().filter(PrinterIntegration::allowPlacement).toList());
    }

    @Redirect(method = "onGameTick", at = @At(value = "INVOKE", target = "Lme/aleksilassila/litematica/printer/guides/Guide;execute(Lnet/minecraft/client/player/LocalPlayer;)Ljava/util/List;"))
    private List<Action> monocle$captureJob(Guide guide, LocalPlayer player) {
        BlockPos pos = ((GuideAccessor) guide).monocle$getState().blockPos;
        return PrinterIntegration.beginJob(this, pos) ? guide.execute(player) : List.of();
    }
}
