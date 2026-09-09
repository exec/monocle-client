/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.commands.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.commands.Command;
import dev.monocle.client.commands.arguments.FakePlayerArgumentType;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.player.FakePlayer;
import dev.monocle.client.utils.entity.fakeplayer.FakePlayerEntity;
import dev.monocle.client.utils.entity.fakeplayer.FakePlayerManager;
import dev.monocle.client.utils.player.ChatUtils;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class FakePlayerCommand extends Command {
    public FakePlayerCommand() {
        super("fake-player", "Manages fake players that you can use for testing.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("add")
            .executes(_ -> {
                FakePlayer fakePlayer = Modules.get().get(FakePlayer.class);
                FakePlayerManager.add(fakePlayer.name.get(), fakePlayer.health.get(), fakePlayer.copyInv.get());
                return SINGLE_SUCCESS;
            })
            .then(argument("name", StringArgumentType.word())
                .executes(context -> {
                    FakePlayer fakePlayer = Modules.get().get(FakePlayer.class);
                    FakePlayerManager.add(StringArgumentType.getString(context, "name"), fakePlayer.health.get(), fakePlayer.copyInv.get());
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("remove")
            .then(argument("fp", FakePlayerArgumentType.create())
                .executes(context -> {
                    FakePlayerEntity fp = FakePlayerArgumentType.get(context);
                    if (fp == null || !FakePlayerManager.contains(fp)) {
                        error("Couldn't find a Fake Player with that name.");
                        return SINGLE_SUCCESS;
                    }

                    FakePlayerManager.remove(fp);
                    info("Removed Fake Player %s.".formatted(fp.getName().getString()));

                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("clear")
            .executes(_ -> {
                FakePlayerManager.clear();
                return SINGLE_SUCCESS;
            })
        );

        builder.then(literal("list")
            .executes(_ -> {
                info("--- Fake Players ((highlight)%s(default)) ---", FakePlayerManager.count());
                FakePlayerManager.forEach(fp -> ChatUtils.info("(highlight)%s".formatted(fp.getName().getString())));
                return SINGLE_SUCCESS;
            })
        );
    }
}
