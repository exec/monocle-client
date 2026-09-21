package dev.monocle.client.gui.utils;

/** Shared responsive thresholds for management screens; values are unscaled GUI units. */
public final class WorkspaceLayout {
    private WorkspaceLayout() {}
    public static double width(double windowPixels, double scale, double minimum, double maximum) {
        return Math.clamp(windowPixels / Math.max(1, scale) - 100, minimum, maximum);
    }
    public static boolean stacked(double contentWidth, double twoColumnMinimum) {
        return contentWidth < twoColumnMinimum;
    }
}
