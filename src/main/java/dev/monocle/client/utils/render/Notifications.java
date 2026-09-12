package dev.monocle.client.utils.render;

import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.render.Render2DEvent;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static dev.monocle.client.MonocleClient.mc;

/** Shared explicit output routing; does not intercept command responses or chat. */
public final class Notifications {
    public enum Output { Chat, Feed, Both }
    /** Module enforces the built-in package boundary; conversation output stays in chat. */
    public static boolean migratedModule(String name) {
        return name != null && !name.isBlank() && !name.equals("community-chat");
    }
    public static final Notifications INSTANCE = new Notifications();
    public static final NotificationFeed FEED = new NotificationFeed();
    private final Map<Long, Double> positions = new HashMap<>();
    private long lastSound;
    private long lastSeenId;

    public static void send(Output output, String source, String key, NotificationFeed.Severity severity, Component message) {
        if (output != Output.Feed) ChatUtils.sendMsg(source, message);
        if (output != Output.Chat) post(source, key, severity, message.getString());
    }

    /** Safe from worker threads: bounded state only, no scheduled tasks or game actions. */
    public static void post(String source, String key, NotificationFeed.Severity severity, String message) {
        Config c = Config.get();
        if (!c.notificationFeed.get()) return;
        FEED.post(source, key, message, severity, System.nanoTime(), lifetime(), c.notificationGrouping.get());
    }

    private static long lifetime() { return (long) (Config.get().notificationDuration.get() * 1_000_000_000L); }

    @EventHandler
    private void onLeft(GameLeftEvent event) { FEED.clear(); positions.clear(); }

    @EventHandler
    private void render(Render2DEvent event) {
        Config c = Config.get();
        if (!c.notificationFeed.get()) { FEED.clear(); positions.clear(); return; }
        long now = System.nanoTime(), lifetime = lifetime();
        List<NotificationFeed.Notice> notices = FEED.snapshot(now, lifetime);
        if (mc.player == null || mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden) return;
        var b = NotificationFeed.bounds(event.screenWidth, event.screenHeight, c.notificationWidth.get(),
            c.notificationRight.get(), c.notificationTop.get(), c.notificationHeight.get());
        if (b.width() < 100 || b.height() < 40) return;
        var graphics = event.graphics;
        var lines = new java.util.ArrayList<List<FormattedCharSequence>>();
        var heights = new java.util.ArrayList<Integer>();
        int total = 0;
        for (var notice : notices) {
            var wrapped = mc.font.split(Component.literal(notice.text()), b.width() - 20);
            int count = Math.min(Math.min(c.notificationLines.get(), (b.height() - 23) / (mc.font.lineHeight + 2)), wrapped.size());
            var shown = new java.util.ArrayList<>(wrapped.subList(0, count));
            if (wrapped.size() > count && count > 0) shown.set(count - 1, FormattedCharSequence.forward("…", net.minecraft.network.chat.Style.EMPTY));
            int height = 23 + count * (mc.font.lineHeight + 2);
            lines.add(shown);
            heights.add(height);
            total += height + 5;
        }
        // Overflow moves the oldest cards up, rather than queuing new events behind them.
        double target = b.y() - Math.max(0, total - 5 - b.height());
        double blend = 1 - Math.exp(-Math.clamp(event.frameTime, 0, .1) * 18);
        graphics.enableScissor(b.x(), b.y(), b.x() + b.width(), b.y() + b.height());
        try {
            for (int i = 0; i < notices.size(); i++) {
                var notice = notices.get(i);
                int height = heights.get(i);
                double previous = positions.getOrDefault(notice.id(), target + 12);
                double animated = previous + (target - previous) * blend;
                positions.put(notice.id(), animated);
                int y = (int) Math.round(animated), x = b.x(), w = b.width();
                if (target + height < b.y() && y + height < b.y()) {
                    FEED.dismiss(notice.id());
                } else {
                    double age = (now - notice.updated()) / (double) lifetime;
                    double alpha = Math.clamp((1 - age) * 5, 0, 1);
                    int accent = switch (notice.severity()) {
                        case Info -> 0xe0bd73;
                        case Success -> 0x80d4ad;
                        case Warning -> 0xf1bd62;
                        case Error -> 0xf08787;
                    };
                    graphics.fill(x + 2, y + 3, x + w, y + height + 2, color(0x000000, .2 * alpha));
                    graphics.fillGradient(x, y, x + w, y + height, color(0x25232b, .94 * alpha), color(0x13151d, .9 * alpha));
                    graphics.fillGradient(x, y, x + 1, y + height, color(0xf3dca0, alpha), color(0x75552c, alpha));
                    graphics.fillGradient(x + w - 1, y, x + w, y + height, color(0xf3dca0, alpha), color(0x75552c, alpha));
                    graphics.fill(x, y, x + w, y + 1, color(0xd6b876, .8 * alpha));
                    String symbol = switch (notice.severity()) {
                        case Info -> "i";
                        case Success -> "+";
                        case Warning -> "!";
                        case Error -> "×";
                    };
                    String title = mc.font.plainSubstrByWidth(symbol + " " + notice.source(), w - 20);
                    graphics.text(mc.font, title, x + 9, y + 6, color(accent, alpha), false);
                    int textY = y + 19;
                    for (var line : lines.get(i)) {
                        graphics.text(mc.font, line, x + 9, textY, color(0xf0edef, alpha), false);
                        textY += mc.font.lineHeight + 2;
                    }
                    graphics.fill(x + 1, y + height - 1, x + 1 + (int) ((w - 2) * Math.clamp(1 - age, 0, 1)), y + height, color(accent, .65 * alpha));
                    if (notice.id() > lastSeenId && c.notificationSound.get() && now - lastSound > 1_000_000_000L && y >= b.y() && y < b.y() + b.height()) {
                        mc.level.playSound(mc.player, mc.player, net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
                            net.minecraft.sounds.SoundSource.AMBIENT, .5f, 1.2f);
                        lastSound = now;
                    }
                    lastSeenId = Math.max(lastSeenId, notice.id());
                }
                target += height + 5;
            }
        } finally { graphics.disableScissor(); }
        positions.keySet().removeIf(id -> notices.stream().noneMatch(n -> n.id() == id));
    }

    private static int color(int rgb, double alpha) { return ((int) (255 * alpha) << 24) | rgb; }
}
