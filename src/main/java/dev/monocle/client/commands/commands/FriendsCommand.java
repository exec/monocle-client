/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.commands.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.commands.Command;
import dev.monocle.client.commands.arguments.FriendArgumentType;
import dev.monocle.client.commands.arguments.PlayerListEntryArgumentType;
import dev.monocle.client.systems.friends.Friend;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.utils.player.ChatUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class FriendsCommand extends Command {
    public FriendsCommand() {
        super("friends", "Manages friends.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("add")
            .then(argument("player", PlayerListEntryArgumentType.create())
                .executes(context -> {
                    GameProfile profile = PlayerListEntryArgumentType.get(context).getProfile();
                    Friend friend = new Friend(profile.name(), profile.id());

                    if (Friends.get().add(friend)) {
                        ChatUtils.sendMsg(friend.hashCode(), ChatFormatting.GRAY, "Added (highlight)%s (default)to friends.".formatted(friend.getName()));
                    } else error("Already friends with that player.");

                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("remove")
            .then(argument("friend", FriendArgumentType.create())
                .executes(context -> {
                    Friend friend = FriendArgumentType.get(context);
                    if (friend == null) {
                        error("Not friends with that player.");
                        return SINGLE_SUCCESS;
                    }

                    if (Friends.get().remove(friend)) {
                        ChatUtils.sendMsg(friend.hashCode(), ChatFormatting.GRAY, "Removed (highlight)%s (default)from friends.".formatted(friend.getName()));
                    } else error("Failed to remove that friend.");

                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("list").executes(_ -> {
                info("--- Friends ((highlight)%s(default)) ---", Friends.get().count());
                Friends.get().forEach(friend -> ChatUtils.info("(highlight)%s".formatted(friend.getName())));
                return SINGLE_SUCCESS;
            })
        );
    }
}
