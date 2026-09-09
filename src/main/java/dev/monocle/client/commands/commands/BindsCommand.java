/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.commands.Command;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.ChatUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

public class BindsCommand extends Command {
    public BindsCommand() {
        super("binds", "List of all bound modules.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(_ -> {
            // Modules
            List<Module> modules = Modules.get().getAll().stream()
                .filter(module -> module.keybind.isSet())
                .toList();

            ChatUtils.info("--- Bound Modules ((highlight)%d(default)) ---", modules.size());

            for (Module module : modules) {
                HoverEvent hoverEvent = new HoverEvent.ShowText(getTooltip(module));

                MutableComponent text = Component.literal(module.title).withStyle(ChatFormatting.WHITE);
                text.setStyle(text.getStyle().withHoverEvent(hoverEvent));

                MutableComponent sep = Component.literal(" - ");
                sep.setStyle(sep.getStyle().withHoverEvent(hoverEvent));
                text.append(sep.withStyle(ChatFormatting.GRAY));

                MutableComponent key = Component.literal(module.keybind.toString());
                key.setStyle(key.getStyle().withHoverEvent(hoverEvent));
                text.append(key.withStyle(ChatFormatting.GRAY));

                ChatUtils.sendMsg(text);
            }

            return SINGLE_SUCCESS;
        });
    }

    private MutableComponent getTooltip(Module module) {
        MutableComponent tooltip = Component.literal(Utils.nameToTitle(module.title)).withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD).append("\n\n");
        tooltip.append(Component.literal(module.description).withStyle(ChatFormatting.WHITE));
        return tooltip;
    }
}
