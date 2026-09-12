package dev.monocle.client.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.commands.Command;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.CommunityChat;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public final class ChatCommand extends Command {
    public ChatCommand() { super("chat", "Select IRC or Minecraft for typed chat. Slash commands always stay in Minecraft."); }
    private CommunityChat chat() { return Modules.get().get(CommunityChat.class); }
    @Override public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(ctx -> { info("Chat target: %s. Use .chat irc or .chat game.", chat().isIrcChat() ? "IRC" : "Minecraft"); return SINGLE_SUCCESS; });
        builder.then(literal("irc").executes(ctx -> { chat().selectChat(true); return SINGLE_SUCCESS; }));
        builder.then(literal("game").executes(ctx -> { chat().selectChat(false); return SINGLE_SUCCESS; }));
    }
}
