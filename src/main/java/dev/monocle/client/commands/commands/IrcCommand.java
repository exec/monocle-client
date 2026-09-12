package dev.monocle.client.commands.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.commands.Command;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.CommunityChat;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public final class IrcCommand extends Command {
    public IrcCommand() { super("irc", "Explicit community chat controls; never sends Minecraft server chat."); }
    private CommunityChat chat() { return Modules.get().get(CommunityChat.class); }
    @Override public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(ctx -> { info("IRC: %s. Commands: connect, disconnect, status, say <message>.", chat().getInfoString()); return SINGLE_SUCCESS; });
        builder.then(literal("connect").executes(ctx -> { chat().disable(); chat().enable(); return SINGLE_SUCCESS; }));
        builder.then(literal("disconnect").executes(ctx -> { chat().disable(); return SINGLE_SUCCESS; }));
        builder.then(literal("status").executes(ctx -> { info("IRC: %s", chat().getInfoString()); return SINGLE_SUCCESS; }));
        builder.then(literal("say").then(argument("message", StringArgumentType.greedyString()).executes(ctx -> {
            try { chat().say(StringArgumentType.getString(ctx, "message")); }
            catch (IllegalArgumentException | IllegalStateException e) { error("%s", e.getMessage()); }
            return SINGLE_SUCCESS;
        })));
    }
}
