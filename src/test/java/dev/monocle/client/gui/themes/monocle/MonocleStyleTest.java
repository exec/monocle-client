package dev.monocle.client.gui.themes.monocle;

import dev.monocle.client.utils.render.color.Color;

/** Run with ./gradlew monocleStyleCheck. No Minecraft window or GPU is required. */
public final class MonocleStyleTest {
    public static void main(String[] args) {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) throw new IllegalStateException("Run with assertions enabled (-ea).");
        assert dev.monocle.client.gui.screens.ModuleSearch.matches("  food NAMED ", "Auto Eat · Protect named food");
        assert dev.monocle.client.gui.screens.ModuleSearch.matches("", "Highway Builder");
        assert !dev.monocle.client.gui.screens.ModuleSearch.matches("food crystal", "Auto Eat food");
        assert !dev.monocle.client.gui.screens.ModuleSearch.advancedGroup("General");
        assert dev.monocle.client.gui.screens.ModuleSearch.advancedGroup("Advanced movement");
        var locale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assert dev.monocle.client.gui.screens.ModuleSearch.matches("INVENTORY", "inventory manager");
        } finally { java.util.Locale.setDefault(locale); }
        var nav = dev.monocle.client.gui.utils.NavigationLayout.wrap(200, 24, new double[]{90, 100, 70});
        assert nav[0].x() == 0 && nav[1].x() == 90 && nav[1].y() == 0;
        assert nav[2].x() == 0 && nav[2].y() == 24 : "Navigation must wrap within narrow windows";
        assert dev.monocle.client.gui.utils.NavigationLayout.wrap(60, 24, new double[]{90})[0].width() == 60;
        var view = new dev.monocle.client.gui.widgets.containers.WView() {};
        view.restoreScroll(120); assert view.scrollPosition() == 120;
        view.restoreScroll(-10); assert view.scrollPosition() == 0;
        view.restoreScroll(Double.NaN); assert view.scrollPosition() == 0;

        Color dark = new Color(20, 40, 60, 80);
        Color light = new Color(220, 180, 140, 200);
        assert MonocleStyle.mix(dark, light, -1).equals(dark);
        assert MonocleStyle.mix(dark, light, 0).equals(dark);
        assert MonocleStyle.mix(dark, light, 1).equals(light);
        assert MonocleStyle.mix(dark, light, 2).equals(light);
        assert MonocleStyle.mix(dark, light, 0.5).equals(new Color(120, 110, 100, 140));
        Color blended = MonocleStyle.mix(dark, light, 0);
        blended.r = 255;
        assert dark.equals(new Color(20, 40, 60, 80)) : "Rendering must not mutate shared theme colors.";
        assert light.equals(new Color(220, 180, 140, 200));

        assert MonocleStyle.alpha(dark, -1).equals(new Color(20, 40, 60, 0));
        assert MonocleStyle.alpha(dark, 0.5).equals(new Color(20, 40, 60, 40));
        assert MonocleStyle.alpha(dark, 2).equals(dark);
        Color faded = MonocleStyle.alpha(dark, 1);
        faded.a = 0;
        assert dark.a == 80 : "Fades must not change the saved palette alpha.";

        assert MonocleStyle.approach(0.25, 1, 0) == 0.25;
        assert MonocleStyle.approach(0.25, 1, -1) == 0.25;
        assert MonocleStyle.approach(1, 1, 1.0 / 60) == 1;
        for (double target : new double[] { 0, 1 }) {
            double value = 1 - target;
            for (int frame = 0; frame < 600; frame++) {
                double next = MonocleStyle.approach(value, target, 1.0 / 60);
                assert Double.isFinite(next) && next >= 0 && next <= 1;
                assert Math.abs(next - target) <= Math.abs(value - target) : "Hover animation must not overshoot.";
                value = next;
            }
            assert Math.abs(value - target) < 0.001 : "Hover animation must settle.";
        }
        assert MonocleStyle.approach(0, 1, 100) <= 1 : "A slow frame must not overshoot.";
        // Hidden/empty controls must not submit invalid geometry to the renderer.
        MonocleStyle.rounded(null, 0, 0, 0, 12, 5, dark);
        MonocleStyle.rounded(null, 0, 0, 12, -1, 5, dark);
        MonocleStyle.rounded(null, 0, 0, 12, 12, 5, Color.CLEAR);
        System.out.println("Monocle style checks passed: color interpolation, non-mutating fades, and bounded hover transitions. GPU/widget visual rendering requires an in-game check.");
    }
}
