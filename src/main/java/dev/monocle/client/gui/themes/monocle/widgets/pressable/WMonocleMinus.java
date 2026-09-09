/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.pressable;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.pressable.WMinus;

public class WMonocleMinus extends WMinus implements MonocleWidget {
    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        double pad = pad();
        double s = theme.scale(2);

        renderBackground(renderer, this, pressed, mouseOver);
        MonocleStyle.rounded(renderer, x + pad, y + height / 2 - s / 2, width - pad * 2, s, s / 2, theme().minusColor.get());
    }
}
