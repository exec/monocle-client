/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.systems.hud.Hud;
import dev.monocle.client.systems.hud.HudElement;
import dev.monocle.client.systems.hud.elements.TextHud;
import dev.monocle.client.systems.modules.Category;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(CrashReport.class)
public abstract class CrashReportMixin {
    @Inject(method = "getDetails(Ljava/lang/StringBuilder;)V", at = @At("TAIL"))
    private void onAddDetails(StringBuilder builder, CallbackInfo ci) {
        builder.append("\n\n-- Monocle Client --\n\n");
        builder.append("Version: ").append(MonocleClient.VERSION).append("\n");
        if (!MonocleClient.BUILD_NUMBER.isEmpty()) {
            builder.append("Build: ").append(MonocleClient.BUILD_NUMBER).append("\n");
        }

        if (Modules.get() != null) {
            boolean modulesActive = false;
            for (Category category : Modules.loopCategories()) {
                List<Module> modules = Modules.get().getGroup(category);
                boolean categoryActive = false;

                for (Module module : modules) {
                    if (module == null || !module.isActive()) continue;

                    if (!modulesActive) {
                        modulesActive = true;
                        builder.append("\n[[ Active Modules ]]\n");
                    }

                    if (!categoryActive) {
                        categoryActive = true;
                        builder.append("\n[")
                            .append(category)
                            .append("]:\n");
                    }

                    builder.append(module.name).append("\n");
                }

            }

        }

        if (Hud.get() != null && Hud.get().active) {
            boolean hudActive = false;
            for (HudElement element : Hud.get()) {
                if (element == null || !element.isActive()) continue;

                if (!hudActive) {
                    hudActive = true;
                    builder.append("\n[[ Active Hud Elements ]]\n");
                }

                if (!(element instanceof TextHud textHud)) builder.append(element.info.name).append("\n");
                else {
                    builder.append("Text\n{")
                        .append(textHud.text.get())
                        .append("}\n");
                    if (textHud.shown.get() != TextHud.Shown.Always) {
                        builder.append("(")
                            .append(textHud.shown.get())
                            .append(textHud.condition.get())
                            .append(")\n");
                    }
                }
            }
        }
    }
}
