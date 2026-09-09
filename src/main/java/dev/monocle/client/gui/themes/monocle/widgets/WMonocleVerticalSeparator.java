/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.WVerticalSeparator;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleVerticalSeparator extends WVerticalSeparator implements MonocleWidget {
    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MonocleGuiTheme theme = theme();
        Color colorEdges = theme.separatorEdges.get();
        Color colorCenter = theme.separatorCenter.get();

        double s = theme.scale(1);
        double offsetX = Math.round(width / 2.0);

        renderer.quad(x + offsetX, y, s, height / 2, colorEdges, colorEdges, colorCenter, colorCenter);
        renderer.quad(x + offsetX, y + height / 2, s, height / 2, colorCenter, colorCenter, colorEdges, colorEdges);
        if (height >= s * 6) {
            renderer.quad(x + offsetX, y + height / 2 - s * 2, s, s * 4, MonocleStyle.alpha(theme.accentColor.get(), 0.6));
        }
    }
}
