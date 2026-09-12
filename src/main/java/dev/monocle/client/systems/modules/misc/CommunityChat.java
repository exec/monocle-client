package dev.monocle.client.systems.modules.misc;

import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.network.IrcConnection;
import dev.monocle.client.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.net.URI;
import java.util.UUID;

public final class CommunityChat extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<String> endpoint = general.add(new StringSetting.Builder().name("endpoint")
        .description("WebSocket IRC URL. Temporary LAN endpoint is unencrypted; never send secrets. Reconnect after changes.")
        .defaultValue("ws://10.0.0.2:8080/irc").build());
    private final Setting<String> nickname = general.add(new StringSetting.Builder().name("nickname")
        .description("Independent chat nickname. Blank generates a random nickname, never your Minecraft username.")
        .defaultValue("").build());
    private final Setting<String> channel = general.add(new StringSetting.Builder().name("channel")
        .description("Single test channel. Reconnect after changes.").defaultValue("#monocle").build());
    private IrcConnection connection;
    private String status = "Disconnected";
    private long started, lastSend;
    private boolean ircChat;

    public CommunityChat() {
        super(Categories.Misc, "community-chat", "Opt-in IRC chat. Select typed-chat destination with .chat irc or .chat game.");
    }
    @Override public void onActivate() {
        onDeactivate();
        String nick = nickname.get().isBlank() ? "Monocle-" + UUID.randomUUID().toString().substring(0, 8) : nickname.get();
        try {
            connection = new IrcConnection(URI.create(endpoint.get()), nick, channel.get());
            started = System.nanoTime();
            lastSend = 0;
            status = "Connecting";
            info("Connecting as %s. LAN chat is unencrypted. Select it with .chat irc.", nick);
        } catch (IllegalArgumentException e) {
            status = e.getMessage();
            error("%s", status);
            disable();
        }
    }
    @Override public void onDeactivate() {
        if (connection != null) connection.close();
        connection = null;
        status = "Disconnected";
    }
    @EventHandler private void onLeft(GameLeftEvent event) { disable(); }
    @EventHandler private void onTick(TickEvent.Post event) {
        if (connection == null) return;
        String next = connection.status();
        if (!next.equals(status)) {
            status = next;
            ChatUtils.sendMsg("IRC", Component.literal(status).withStyle(ChatFormatting.AQUA));
        }
        for (int i = 0; i < 8; i++) {
            String text = connection.poll();
            if (text == null) break;
            // Literal components: never parse server text as JSON, commands, links, or Starscript.
            ChatUtils.sendMsg("IRC", Component.literal(text).withStyle(ChatFormatting.AQUA));
        }
        if (!connection.joined() && System.nanoTime() - started > 30_000_000_000L) {
            warning("IRC did not join a channel: %s. Reconnect manually.", status);
            disable();
        }
    }
    public void say(String text) {
        if (connection == null || !isActive()) throw new IllegalStateException("Connect first with .irc connect.");
        if (System.nanoTime() - lastSend < 1_000_000_000L) throw new IllegalStateException("Wait one second between messages.");
        connection.say(text);
        lastSend = System.nanoTime();
    }
    public boolean isIrcChat() { return ircChat; }
    public void selectChat(boolean irc) {
        ircChat = irc;
        if (irc && !isActive()) enable();
        info(irc ? "Chat target: IRC. Slash commands still go to Minecraft. Use .chat game to switch back."
            : "Chat target: Minecraft. IRC can still receive messages.");
    }

    /** Intentionally independent of connection/module state: a failed IRC send must never leak to game chat. */
    public static boolean routeToIrc(boolean selected, String message, String clientPrefix, String pathingPrefix) {
        return selected && !message.isBlank() && !message.startsWith("/")
            && (clientPrefix.isEmpty() || !message.startsWith(clientPrefix))
            && (pathingPrefix.isEmpty() || !message.startsWith(pathingPrefix));
    }
    @Override public String getInfoString() { return status; }
    @Override public CompoundTag toTag() {
        CompoundTag tag = super.toTag();
        tag.putBoolean("active", false);
        return tag;
    }
    @Override public Module fromTag(CompoundTag tag) {
        CompoundTag safe = tag.copy();
        safe.putBoolean("active", false);
        return super.fromTag(safe);
    }
}
