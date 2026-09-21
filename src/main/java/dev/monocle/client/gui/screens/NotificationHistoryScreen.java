package dev.monocle.client.gui.screens;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.utils.render.NotificationFeed;
import dev.monocle.client.utils.render.Notifications;

import java.util.Locale;

/** Session-only, inspectable view of the same bounded feed rendered in game. */
public final class NotificationHistoryScreen extends WindowScreen {
    public NotificationHistoryScreen(GuiTheme theme) { super(theme, "Notification history"); }

    @Override public void initWidgets() {
        var history = Notifications.FEED.history();
        add(theme.label("Newest first · grouped incidents · cleared when you leave the world", 520));
        if (history.isEmpty()) add(theme.label("No feed notifications in this world yet."));
        long now = System.nanoTime();
        for (var notice : history.reversed()) {
            String count = notice.count() > 1 ? " ×" + notice.count() : "";
            add(theme.label(notice.source() + count + " · " + notice.severity() + " · " + age(now, notice.updated())
                + "\n" + notice.text(), 520));
        }
        add(theme.button("Clear history and visible feed")).expandX().widget().action = () -> {
            Notifications.FEED.clear();
            reload();
        };
    }

    static String age(long now, long then) {
        long seconds = Math.max(0, (now - then) / 1_000_000_000L);
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return seconds / 60 + "m ago";
        return String.format(Locale.ROOT, "%.1fh ago", seconds / 3600.0);
    }
}
