package dev.monocle.client.utils.render;

import java.util.ArrayList;
import java.util.List;

/** Bounded, thread-safe feed state. No Minecraft objects or background tasks. */
public final class NotificationFeed {
    public enum Severity { Info, Success, Warning, Error }
    public record Notice(long id, String source, String key, String text, Severity severity, long updated) { }
    private final List<Notice> active = new ArrayList<>();
    private final List<Notice> history = new ArrayList<>();
    private long nextId;

    public synchronized void post(String source, String key, String text, Severity severity, long now, long lifetime, boolean group) {
        expire(now, lifetime);
        source = plain(source, 48);
        key = plain(key, 128);
        text = plain(text, 512);
        if (text.isBlank()) return;
        if (group && !key.isEmpty()) {
            for (int i = 0; i < active.size(); i++) {
                Notice old = active.get(i);
                if (old.source.equals(source) && old.key.equals(key)) {
                    Notice updated = new Notice(old.id, source, key, text, severity, now);
                    active.set(i, updated); // Updating a card never changes its position.
                    remember(updated);
                    return;
                }
            }
        }
        Notice notice = new Notice(++nextId, source, key, text, severity, now);
        active.add(notice);
        if (active.size() > 64) active.removeFirst();
        remember(notice);
    }

    private void remember(Notice notice) {
        history.add(notice);
        if (history.size() > 100) history.removeFirst();
    }

    private void expire(long now, long lifetime) { active.removeIf(n -> now - n.updated >= lifetime); }

    public synchronized List<Notice> snapshot(long now, long lifetime) {
        expire(now, lifetime);
        return List.copyOf(active);
    }

    /** Once scrolled out, a card cannot reappear as newer cards expire. */
    public synchronized void dismiss(long id) { active.removeIf(n -> n.id == id); }
    public synchronized List<Notice> history() { return List.copyOf(history); }
    public synchronized void clear() { active.clear(); history.clear(); }

    public static String plain(String value, int limit) {
        if (value == null) return "";
        value = value.replaceAll("(?i)§[0-9a-fk-or]", "");
        StringBuilder clean = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != '§')
            .limit(limit).forEach(clean::appendCodePoint);
        return clean.toString();
    }

    public record Bounds(int x, int y, int width, int height) { }
    public static Bounds bounds(int screenWidth, int screenHeight, int width, int right, int top, int percent) {
        if (screenWidth < 16 || screenHeight < 16) return new Bounds(0, 0, 0, 0);
        int w = Math.max(0, Math.min(width, screenWidth - 16));
        int y = Math.clamp(top, 8, Math.max(8, screenHeight - 8));
        int h = Math.max(0, Math.min(screenHeight * Math.clamp(percent, 10, 100) / 100, screenHeight - y - 8));
        return new Bounds(Math.max(8, screenWidth - Math.max(8, right) - w), y, w, h);
    }
}
