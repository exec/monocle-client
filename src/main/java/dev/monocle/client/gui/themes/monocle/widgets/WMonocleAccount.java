/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.WAccount;
import dev.monocle.client.systems.accounts.Account;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleAccount extends WAccount implements MonocleWidget {
    private double hoverProgress;

    public WMonocleAccount(WidgetScreen screen, Account<?> account) {
        super(screen, account);
    }

    @Override
    protected Color loggedInColor() {
        return theme().loggedInColor.get();
    }

    @Override
    protected Color accountTypeColor() {
        return theme().textSecondaryColor.get();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        hoverProgress = MonocleStyle.approach(hoverProgress, mouseOver && !theme.disableHoverColor ? 1 : 0, delta);
        MonocleStyle.rounded(renderer, x, y, width, height, theme.scale(4),
            MonocleStyle.alpha(theme().backgroundColor.get(false, true), 0.35 + hoverProgress * 0.4));
    }
}
