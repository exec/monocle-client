/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin.baritone;

import baritone.api.pathing.goals.GoalBlock;
import baritone.command.defaults.ComeCommand;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.Freecam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(ComeCommand.class)
public abstract class ComeCommandMixin {
    @ModifyArgs(method = "execute", at = @At(value = "INVOKE", target = "Lbaritone/api/process/ICustomGoalProcess;setGoalAndPath(Lbaritone/api/pathing/goals/Goal;)V"), remap = false)
    private void getComeCommandTarget(Args args) {
        Freecam freecam = Modules.get().get(Freecam.class);
        if (freecam.isActive()) {
            float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            args.set(0, new GoalBlock((int) freecam.getX(tickDelta), (int) freecam.getY(tickDelta), (int) freecam.getZ(tickDelta)));
        }
    }
}
