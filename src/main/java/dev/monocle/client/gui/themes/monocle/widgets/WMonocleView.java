/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.containers.WView;

public class WMonocleView extends WView implements MonocleWidget {
    private double hoverProgress;

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (canScroll && hasScrollBar) {
            hoverProgress = MonocleStyle.approach(hoverProgress, focused || (handleMouseOver && !theme.disableHoverColor) ? 1 : 0, delta);
            double s = theme.scale(1);
            MonocleStyle.rounded(renderer, handleX() + s * 2, y, handleWidth() - s * 4, height, s,
                MonocleStyle.alpha(theme().outlineColor.get(), 0.65));
            MonocleStyle.rounded(renderer, handleX() + s, handleY(), handleWidth() - s * 2, handleHeight(), s * 2,
                MonocleStyle.mix(theme().scrollbarColor.get(), theme().accentColor.get(), hoverProgress * 0.65));
        }
    }
}
