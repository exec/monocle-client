/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WWindow;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleWindow extends WWindow implements MonocleWidget {
    public WMonocleWindow(WWidget icon, String title) {
        super(icon, title);
    }

    @Override
    protected WHeader header(WWidget icon) {
        return new WMonocleHeader(icon);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        renderPanel(renderer, x, y, width, expanded || animProgress > 0 ? height : header.height);
    }

    private class WMonocleHeader extends WHeader {
        private double hoverProgress;

        public WMonocleHeader(WWidget icon) {
            super(icon);
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            hoverProgress = MonocleStyle.approach(hoverProgress, dragging || (mouseOver && !theme.disableHoverColor) ? 1 : 0, delta);
            double s = theme.scale(1);
            Color surface = MonocleStyle.mix(theme().backgroundColor.get(), theme().accentColor.get(), 0.07 + hoverProgress * 0.05);
            MonocleStyle.rounded(renderer, x + s * 2, y + s * 2, width - s * 4, height - s * 3, theme.scale(5), surface);

            Color edge = MonocleStyle.alpha(theme().accentColor.get(), 0.7 + hoverProgress * 0.15);
            Color shine = MonocleStyle.alpha(MonocleStyle.mix(theme().accentColor.get(), theme().titleTextColor.get(), 0.65), 0.82);
            Color faded = MonocleStyle.alpha(edge, 0);
            double inset = theme.scale(7), span = Math.max(0, width - inset * 2);
            // A fixed, off-center reflection gives the fine trim a polished-metal finish.
            renderer.quad(x + inset, y + s * 2, span * 0.38, s, faded, MonocleStyle.alpha(shine, 0.55));
            renderer.quad(x + inset + span * 0.38, y + s * 2, span * 0.62, s, MonocleStyle.alpha(shine, 0.55), faded);
            renderer.quad(x + inset, y + height - s, span * 0.38, s, faded, shine);
            renderer.quad(x + inset + span * 0.38, y + height - s, span * 0.24, s, shine, edge);
            renderer.quad(x + inset + span * 0.62, y + height - s, span * 0.38, s, edge, faded);

            // Use the title's existing leading space; never move the label or its hitbox.
            if (WMonocleWindow.this.icon == null && beforeHeaderInit == null && !cells.isEmpty()
                && cells.getFirst().widget().x - x >= theme.scale(20)) {
                double size = theme.scale(9);
                double ringX = x + theme.scale(7);
                double ringY = y + (height - size) / 2 - s;
                MonocleStyle.rounded(renderer, ringX, ringY, size, size, size / 2, theme().accentColor.get());
                MonocleStyle.rounded(renderer, ringX + s, ringY + s, size - s * 2, size - s * 2, size / 2, surface);
                renderer.quad(ringX + size - s * 2, ringY + size - s, s, s * 3, edge);
            }
        }
    }
}
