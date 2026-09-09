/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.commands.Command;
import dev.monocle.client.commands.arguments.PlayerArgumentType;
import dev.monocle.client.events.monocle.KeyInputEvent;
import dev.monocle.client.events.monocle.MouseClickEvent;
import dev.monocle.client.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;

public class SpectateCommand extends Command {

    private final StaticListener shiftListener = new StaticListener();

    public SpectateCommand() {
        super("spectate", "Allows you to spectate nearby players");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("reset").executes(_ -> {
            mc.setCameraEntity(mc.player);
            return SINGLE_SUCCESS;
        }));

        builder.then(argument("player", PlayerArgumentType.create()).executes(context -> {
            mc.setCameraEntity(PlayerArgumentType.get(context));
            mc.player.sendSystemMessage(Component.literal("Sneak to un-spectate."));
            MonocleClient.EVENT_BUS.subscribe(shiftListener);
            return SINGLE_SUCCESS;
        }));
    }

    private static class StaticListener {
        @EventHandler
        private void onKey(KeyInputEvent event) {
            if (Input.isPressed(mc.options.keyShift)) {
                mc.setCameraEntity(mc.player);
                event.cancel();
                MonocleClient.EVENT_BUS.unsubscribe(this);
            }
        }

        @EventHandler
        private void onMouse(MouseClickEvent event) {
            if (Input.isPressed(mc.options.keyShift)) {
                mc.setCameraEntity(mc.player);
                event.cancel();
                MonocleClient.EVENT_BUS.unsubscribe(this);
            }
        }
    }
}
