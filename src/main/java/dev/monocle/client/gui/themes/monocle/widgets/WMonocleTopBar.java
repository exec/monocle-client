/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.WTopBar;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleTopBar extends WTopBar implements MonocleWidget {
    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        renderPanel(renderer, x, y, width, height);
    }

    @Override
    protected void renderButton(GuiRenderer renderer, WWidget button, boolean selected, boolean pressed, boolean hovered) {
        hovered &= !theme.disableHoverColor;
        double s = theme.scale(1);
        Color surface = selected ? theme().moduleBackground.get() : getButtonColor(pressed, hovered);
        MonocleStyle.rounded(renderer, button.x + s, button.y + s, button.width - s * 2, button.height - s * 2,
            theme.scale(4), MonocleStyle.alpha(surface, selected || pressed || hovered ? 1 : 0.3));

        if (selected || hovered) {
            double inset = button.pad();
            MonocleStyle.rounded(renderer, button.x + inset, button.y + button.height - s * 2,
                button.width - inset * 2, s, s / 2,
                MonocleStyle.alpha(selected ? theme().checkboxColor.get() : theme().accentColor.get(), selected ? 1 : 0.45));
        }
    }

    @Override
    protected Color getButtonColor(boolean pressed, boolean hovered) {
        return theme().backgroundColor.get(pressed, hovered);
    }

    @Override
    protected Color getNameColor() {
        return theme().textColor.get();
    }
}
