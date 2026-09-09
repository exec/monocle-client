package dev.monocle.client.gui.themes.monocle;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.utils.render.color.Color;

/** Shared, batched geometry and transitions for the Monocle skin. No extra render pass. */
public final class MonocleStyle {
    private static final int CORNER_SEGMENTS = 8;
    private static final double[] SIN = new double[CORNER_SEGMENTS + 1];
    private static final double[] COS = new double[CORNER_SEGMENTS + 1];

    static {
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            double angle = Math.PI * 0.5 * i / CORNER_SEGMENTS;
            SIN[i] = Math.sin(angle);
            COS[i] = Math.cos(angle);
        }
    }

    private MonocleStyle() {}

    public static Color mix(Color from, Color to, double amount) {
        double t = Math.clamp(amount, 0, 1);
        return new Color((int) Math.round(from.r + (to.r - from.r) * t),
            (int) Math.round(from.g + (to.g - from.g) * t),
            (int) Math.round(from.b + (to.b - from.b) * t),
            (int) Math.round(from.a + (to.a - from.a) * t));
    }

    public static Color alpha(Color color, double opacity) {
        return new Color(color.r, color.g, color.b, (int) Math.round(color.a * Math.clamp(opacity, 0, 1)));
    }

    public static double approach(double current, double target, double delta) {
        return current + (target - current) * (1 - Math.exp(-18 * Math.clamp(delta, 0, 1)));
    }

    public static void rounded(GuiRenderer renderer, double x, double y, double width, double height, double radius, Color color) {
        rounded(renderer, x, y, width, height, radius, color, color);
    }

    public static void rounded(GuiRenderer renderer, double x, double y, double width, double height, double radius, Color top, Color bottom) {
        if (!(width > 0 && height > 0) || top.a == 0 && bottom.a == 0) return;
        double r = Math.clamp(radius, 0, Math.min(width, height) / 2);
        if (r == 0) {
            renderer.quad(x, y, width, height, top, top, bottom, bottom);
            return;
        }
        // Disjoint strips and quarter fans avoid darker overlapping corners on translucent surfaces.
        if (width > r * 2) renderer.quad(x + r, y, width - r * 2, height, top, top, bottom, bottom);
        if (height > r * 2) {
            Color upper = shade(top, bottom, r / height), lower = shade(top, bottom, 1 - r / height);
            renderer.quad(x, y + r, r, height - r * 2, upper, upper, lower, lower);
            renderer.quad(x + width - r, y + r, r, height - r * 2, upper, upper, lower, lower);
        }
        for (int corner = 0; corner < 4; corner++) {
            double cx = x + (corner % 2 == 0 ? r : width - r);
            double cy = y + (corner < 2 ? r : height - r);
            int sx = corner % 2 == 0 ? -1 : 1;
            int sy = corner < 2 ? -1 : 1;
            Color center = shade(top, bottom, (cy - y) / height);
            for (int i = 0; i < CORNER_SEGMENTS; i++) {
                double x1 = cx + sx * r * COS[i], y1 = cy + sy * r * SIN[i];
                double x2 = cx + sx * r * COS[i + 1], y2 = cy + sy * r * SIN[i + 1];
                Color c1 = shade(top, bottom, (y1 - y) / height), c2 = shade(top, bottom, (y2 - y) / height);
                // Match Renderer2D.quad's winding: the GUI pipeline culls back faces.
                if (sx == sy) renderer.triangle(cx, cy, x2, y2, x1, y1, center, c2, c1);
                else renderer.triangle(cx, cy, x1, y1, x2, y2, center, c1, c2);
            }
        }
    }

    private static Color shade(Color top, Color bottom, double position) {
        return top == bottom ? top : mix(top, bottom, position);
    }
}
