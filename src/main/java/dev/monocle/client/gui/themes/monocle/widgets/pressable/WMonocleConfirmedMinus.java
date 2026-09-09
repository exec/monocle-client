/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.pressable;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.pressable.WConfirmedMinus;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleConfirmedMinus extends WConfirmedMinus implements MonocleWidget {
    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MonocleGuiTheme theme = theme();
        double pad = pad();
        double s = theme.scale(2);

        Color outline = pressedOnce ? theme.minusColor.get() : theme.outlineColor.get(pressed, mouseOver);
        Color fg = pressedOnce ? theme.backgroundColor.get(pressed, mouseOver) : theme().minusColor.get();
        Color bg = pressedOnce ? theme().minusColor.get() : theme.backgroundColor.get(pressed, mouseOver);

        renderBackground(renderer, this, outline, bg);
        MonocleStyle.rounded(renderer, x + pad, y + height / 2 - s / 2, width - pad * 2, s, s / 2, fg);
    }
}
