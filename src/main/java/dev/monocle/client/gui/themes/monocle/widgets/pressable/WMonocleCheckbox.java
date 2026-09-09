/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.pressable;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.pressable.WCheckbox;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleCheckbox extends WCheckbox implements MonocleWidget {
    private double animProgress;
    private double hoverProgress;

    public WMonocleCheckbox(boolean checked) {
        super(checked);
        animProgress = checked ? 1 : 0;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MonocleGuiTheme theme = theme();

        animProgress = MonocleStyle.approach(animProgress, checked ? 1 : 0, delta);
        hoverProgress = MonocleStyle.approach(hoverProgress, pressed || (mouseOver && !theme.disableHoverColor) ? 1 : 0, delta);

        double size = theme.textHeight();
        double cx = x + width / 2;
        double cy = y + height / 2;
        double ring = theme.scale(1.5);
        Color tint = MonocleStyle.mix(theme.textSecondaryColor.get(), theme.checkboxColor.get(), animProgress);

        MonocleStyle.rounded(renderer, x + theme.scale(1), y + theme.scale(1), width - theme.scale(2), height - theme.scale(2), theme.scale(5),
            MonocleStyle.alpha(tint, 0.07 * animProgress + 0.1 * hoverProgress));
        MonocleStyle.rounded(renderer, cx - size / 2, cy - size / 2, size, size, size / 2, tint);
        MonocleStyle.rounded(renderer, cx - size / 2 + ring, cy - size / 2 + ring, size - ring * 2, size - ring * 2, size / 2,
            theme.backgroundColor.get(pressed, false));
        double dot = size * 0.44 * animProgress;
        MonocleStyle.rounded(renderer, cx - dot / 2, cy - dot / 2, dot, dot, dot / 2, theme.checkboxColor.get());
    }
}
