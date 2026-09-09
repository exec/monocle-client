/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.input;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleDropdown<T> extends WDropdown<T> implements MonocleWidget {
    private double hoverProgress;

    public WMonocleDropdown(T[] values, T value) {
        super(values, value);
    }

    @Override
    protected WDropdownRoot createRootWidget() {
        return new WRoot();
    }

    @Override
    protected WDropdownValue createValueWidget() {
        return new WValue();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MonocleGuiTheme theme = theme();
        double pad = pad();
        double s = theme.textHeight();

        hoverProgress = MonocleStyle.approach(hoverProgress, mouseOver && !theme.disableHoverColor ? 1 : 0, delta);
        renderBackground(renderer, this,
            expanded ? theme.accentColor.get() : MonocleStyle.mix(theme.outlineColor.get(), theme.outlineColor.get(pressed, true), hoverProgress),
            MonocleStyle.mix(theme.backgroundColor.get(), theme.backgroundColor.get(pressed, true), hoverProgress));

        String text = get().toString();
        double w = theme.textWidth(text);
        renderer.text(text, x + pad + maxValueWidth / 2 - w / 2, y + pad, theme.textColor.get(), false);

        double arrow = s * 0.55;
        renderer.rotatedQuad(x + pad + maxValueWidth + pad + (s - arrow) / 2, y + pad + (s - arrow) / 2,
            arrow, arrow, animProgress * 180, GuiRenderer.TRIANGLE, expanded ? theme.accentColor.get() : theme.textSecondaryColor.get());
    }

    private static class WRoot extends WDropdownRoot implements MonocleWidget {
        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            renderPanel(renderer, x, y, width, height);
        }
    }

    private class WValue extends WDropdownValue implements MonocleWidget {
        private double hoverProgress;

        @Override
        protected void onCalculateSize() {
            double pad = pad();

            width = pad + theme.textWidth(value.toString()) + pad;
            height = pad + theme.textHeight() + pad;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            MonocleGuiTheme theme = theme();

            boolean selected = WMonocleDropdown.this.get().equals(value);
            hoverProgress = MonocleStyle.approach(hoverProgress, mouseOver ? 1 : 0, delta);
            Color tint = selected ? theme.checkboxColor.get() : theme.accentColor.get();
            MonocleStyle.rounded(renderer, x, y, width, height, theme.scale(3),
                MonocleStyle.alpha(tint, (selected ? 0.1 : 0) + hoverProgress * 0.09));
            if (selected) MonocleStyle.rounded(renderer, x + theme.scale(1), y + pad(), theme.scale(2),
                height - pad() * 2, theme.scale(1), theme.checkboxColor.get());

            String text = value.toString();
            renderer.text(text, x + width / 2 - theme.textWidth(text) / 2, y + pad(), theme.textColor.get(), false);
        }
    }
}
