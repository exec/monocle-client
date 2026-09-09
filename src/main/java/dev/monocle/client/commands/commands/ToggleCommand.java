/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.commands.Command;
import dev.monocle.client.commands.arguments.ModuleArgumentType;
import dev.monocle.client.systems.hud.Hud;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class ToggleCommand extends Command {
    public ToggleCommand() {
        super("toggle", "Toggles a module.", "t");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder
            .then(literal("all")
                .then(literal("on")
                    .executes(_ -> {
                        Modules.get().getAll().forEach(Module::enable);
                        Hud.get().active = true;
                        return SINGLE_SUCCESS;
                    })
                )
                .then(literal("off")
                    .executes(_ -> {
                        Modules.get().getActive().forEach(Module::toggle);
                        Hud.get().active = false;
                        return SINGLE_SUCCESS;
                    })
                )
            )
            .then(argument("module", ModuleArgumentType.create())
                .executes(context -> {
                    Module m = ModuleArgumentType.get(context);
                    m.toggle();
                    m.sendToggledMsg();
                    return SINGLE_SUCCESS;
                })
                .then(literal("on")
                    .executes(context -> {
                        Module m = ModuleArgumentType.get(context);
                        m.enable();
                        return SINGLE_SUCCESS;
                    }))
                .then(literal("off")
                    .executes(context -> {
                        Module m = ModuleArgumentType.get(context);
                        m.disable();
                        return SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("hud")
                .executes(_ -> {
                    Hud.get().active = !(Hud.get().active);
                    return SINGLE_SUCCESS;
                })
                .then(literal("on")
                    .executes(_ -> {
                        Hud.get().active = true;
                        return SINGLE_SUCCESS;
                    })
                ).then(literal("off")
                    .executes(_ -> {
                        Hud.get().active = false;
                        return SINGLE_SUCCESS;
                    })
                )
            );
    }
}
