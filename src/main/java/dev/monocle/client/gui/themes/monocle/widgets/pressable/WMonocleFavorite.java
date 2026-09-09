/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.pressable;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.pressable.WFavorite;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleFavorite extends WFavorite implements MonocleWidget {
    private double hoverProgress;

    public WMonocleFavorite(boolean checked) {
        super(checked);
    }

    @Override
    protected Color getColor() {
        return checked ? theme().favoriteColor.get() : MonocleStyle.mix(theme().textSecondaryColor.get(), theme().favoriteColor.get(), hoverProgress);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        hoverProgress = MonocleStyle.approach(hoverProgress, pressed || (mouseOver && !theme.disableHoverColor) ? 1 : 0, delta);
        MonocleStyle.rounded(renderer, x, y, width, height, theme.scale(5),
            MonocleStyle.alpha(theme().favoriteColor.get(), hoverProgress * 0.1));
        super.onRender(renderer, mouseX, mouseY, delta);
    }
}
