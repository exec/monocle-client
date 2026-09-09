/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.utils.BaseWidget;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.utils.render.color.Color;

public interface MonocleWidget extends BaseWidget {
    default MonocleGuiTheme theme() {
        return (MonocleGuiTheme) getTheme();
    }

    default void renderBackground(GuiRenderer renderer, WWidget widget, Color outlineColor, Color backgroundColor) {
        double s = theme().scale(1), radius = theme().scale(5);
        MonocleStyle.rounded(renderer, widget.x, widget.y, widget.width, widget.height, radius,
            MonocleStyle.mix(outlineColor, theme().textColor.get(), 0.12), MonocleStyle.mix(outlineColor, Color.BLACK, 0.2));
        MonocleStyle.rounded(renderer, widget.x + s, widget.y + s, widget.width - s * 2, widget.height - s * 2,
            radius - s, backgroundColor);
        if (widget.width > radius * 2 && widget.height > s * 3) {
            Color highlight = MonocleStyle.alpha(theme().textColor.get(), 0.035);
            renderer.quad(widget.x + radius, widget.y + s, widget.width - radius * 2, s, highlight);
        }
    }

    default void renderBackground(GuiRenderer renderer, WWidget widget, boolean pressed, boolean mouseOver) {
        MonocleGuiTheme theme = theme();
        renderBackground(renderer, widget, theme.outlineColor.get(pressed, mouseOver), theme.backgroundColor.get(pressed, mouseOver));
    }

    default void renderInset(GuiRenderer renderer, WWidget widget, boolean focused, boolean hovered) {
        MonocleGuiTheme theme = theme();
        renderBackground(renderer, widget, focused ? theme.accentColor.get() : theme.outlineColor.get(false, hovered),
            MonocleStyle.mix(theme.backgroundColor.get(), Color.BLACK, 0.22));
    }

    default void renderPanel(GuiRenderer renderer, double x, double y, double width, double height) {
        double s = theme().scale(1), radius = theme().scale(7);
        // Soft local shadow, using the same colored batch rather than a full-screen blur pass.
        for (int i = 3; i > 0; i--) {
            double spread = theme().scale(i * 2);
            MonocleStyle.rounded(renderer, x - spread, y - spread + theme().scale(2), width + spread * 2, height + spread * 2,
                radius + spread, new Color(0, 0, 0, 12));
        }
        Color gold = MonocleStyle.mix(theme().outlineColor.get(), theme().accentColor.get(), 0.8);
        Color highlight = MonocleStyle.mix(gold, theme().titleTextColor.get(), 0.5);
        Color bronze = MonocleStyle.mix(gold, Color.BLACK, 0.42);
        MonocleStyle.rounded(renderer, x, y, width, height, radius, highlight, bronze);
        double rim = theme().scale(1.5);
        MonocleStyle.rounded(renderer, x + rim, y + rim, width - rim * 2, height - rim * 2, radius - rim,
            theme().backgroundColor.get());
        if (width > radius * 2 && height > radius * 2) {
            Color top = MonocleStyle.alpha(theme().textColor.get(), 0.025);
            Color bottom = MonocleStyle.alpha(top, 0);
            renderer.quad(x + radius, y + s, width - radius * 2, Math.min(height - radius, theme().scale(100)), top, top, bottom, bottom);
        }
    }
}
