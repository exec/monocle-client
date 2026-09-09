/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.commands.Command;
import dev.monocle.client.renderer.Fonts;
import dev.monocle.client.systems.Systems;
import dev.monocle.client.systems.friends.Friend;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.utils.network.MonocleExecutor;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class ReloadCommand extends Command {
    public ReloadCommand() {
        super("reload", "Reloads many systems.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(_ -> {
            warning("Reloading systems, this may take a while.");

            Systems.load();
            Fonts.refresh();
            MonocleExecutor.execute(() -> Friends.get().forEach(Friend::updateInfo));

            return SINGLE_SUCCESS;
        });
    }
}
