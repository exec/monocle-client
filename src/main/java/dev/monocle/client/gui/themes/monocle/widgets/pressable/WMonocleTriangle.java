/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.pressable;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.pressable.WTriangle;

public class WMonocleTriangle extends WTriangle implements MonocleWidget {
    private double hoverProgress;

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        hoverProgress = MonocleStyle.approach(hoverProgress, pressed || (mouseOver && !theme.disableHoverColor) ? 1 : 0, delta);
        double inset = width * 0.23;
        renderer.rotatedQuad(x + inset, y + inset, width - inset * 2, height - inset * 2, rotation, GuiRenderer.TRIANGLE,
            MonocleStyle.mix(theme().textSecondaryColor.get(), theme().accentColor.get(), hoverProgress));
    }
}
