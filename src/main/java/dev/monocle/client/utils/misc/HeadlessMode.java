package dev.monocle.client.utils.misc;

/** Opt-in compatibility for a launcher-provided headless graphics backend. */
public final class HeadlessMode {
    private HeadlessMode() {}
    public static boolean enabled() { return Boolean.getBoolean("monocle.headless"); }
}
