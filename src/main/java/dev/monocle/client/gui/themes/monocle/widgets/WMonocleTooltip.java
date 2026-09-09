/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.WTooltip;

public class WMonocleTooltip extends WTooltip implements MonocleWidget {
    public WMonocleTooltip(String text) {
        super(text);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        renderPanel(renderer, x, y, width, height);
        MonocleStyle.rounded(renderer, x + theme.scale(1), y + theme.scale(4), theme.scale(1), height - theme.scale(8),
            theme.scale(0.5), theme().accentColor.get());
    }
}
