package dev.monocle.client.gui.utils;

/** Pixel positions for wrapping navigation buttons, also testable without a renderer. */
public final class NavigationLayout {
    private NavigationLayout() {}
    public record Item(double x, double y, double width) {}
    public static Item[] wrap(double available, double rowHeight, double[] widths) {
        Item[] result = new Item[widths.length];
        double x = 0, y = 0;
        for (int i = 0; i < widths.length; i++) {
            double width = Math.min(Math.max(1, available), widths[i]);
            if (x > 0 && x + width > available) { x = 0; y += rowHeight; }
            result[i] = new Item(x, y, width);
            x += width;
        }
        return result;
    }
}
