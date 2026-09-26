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
    public static int columns(double width, double minimum, double gap, int maximum) {
        if (!Double.isFinite(width) || !Double.isFinite(minimum) || !Double.isFinite(gap) || minimum <= 0 || gap < 0 || maximum < 1) return 1;
        return Math.clamp((int) Math.floor((width + gap) / (minimum + gap)), 1, maximum);
    }
}
