/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.input;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.input.WSlider;

public class WMonocleSlider extends WSlider implements MonocleWidget {
    private double hoverProgress;

    public WMonocleSlider(double value, double min, double max) {
        super(value, min, max);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        double valueWidth = valueWidth();
        hoverProgress = MonocleStyle.approach(hoverProgress, dragging || (handleMouseOver && !theme.disableHoverColor) ? 1 : 0, delta);

        renderBar(renderer, valueWidth);
        renderHandle(renderer, valueWidth);
    }

    private void renderBar(GuiRenderer renderer, double valueWidth) {
        MonocleGuiTheme theme = theme();

        double s = theme.scale(4);
        double handleSize = handleSize();

        double x = this.x + handleSize / 2;
        double y = this.y + height / 2 - s / 2;

        double length = Math.max(0, width - handleSize);
        MonocleStyle.rounded(renderer, x, y, length, s, s / 2, theme.sliderRight.get());
        MonocleStyle.rounded(renderer, x, y + theme.scale(1), valueWidth, s - theme.scale(2), s / 2, theme.sliderLeft.get());
    }

    private void renderHandle(GuiRenderer renderer, double valueWidth) {
        MonocleGuiTheme theme = theme();
        double s = handleSize();
        double inset = s * 0.14;
        double size = s - inset * 2;
        double ring = theme.scale(1.5);
        double hx = x + valueWidth + inset;
        double hy = y + (height - size) / 2;

        MonocleStyle.rounded(renderer, x + valueWidth, y + (height - s) / 2, s, s, s / 2,
            MonocleStyle.alpha(theme.accentColor.get(), hoverProgress * 0.17));
        MonocleStyle.rounded(renderer, hx, hy, size, size, size / 2,
            MonocleStyle.mix(theme.sliderHandle.get(), theme.sliderHandle.get(dragging, true), hoverProgress));
        MonocleStyle.rounded(renderer, hx + ring, hy + ring, size - ring * 2, size - ring * 2, size / 2,
            theme.backgroundColor.get(true, false));
        double core = size * 0.28;
        MonocleStyle.rounded(renderer, hx + (size - core) / 2, hy + (size - core) / 2, core, core, core / 2, theme.sliderLeft.get());
    }
}
