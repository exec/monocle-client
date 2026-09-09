/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.pressable;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.renderer.packer.GuiTexture;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.pressable.WButton;

public class WMonocleButton extends WButton implements MonocleWidget {
    private double hoverProgress;
    private double pressProgress;

    public WMonocleButton(String text, GuiTexture texture) {
        super(text, texture);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MonocleGuiTheme theme = theme();
        double pad = pad();

        hoverProgress = MonocleStyle.approach(hoverProgress, mouseOver && !theme.disableHoverColor ? 1 : 0, delta);
        pressProgress = MonocleStyle.approach(pressProgress, pressed ? 1 : 0, delta);
        renderBackground(renderer, this,
            MonocleStyle.mix(theme.outlineColor.get(), theme.outlineColor.get(pressed, true), Math.max(hoverProgress, pressProgress)),
            MonocleStyle.mix(theme.backgroundColor.get(), theme.backgroundColor.get(pressed, true), Math.max(hoverProgress, pressProgress)));
        double contentY = y + pad + theme.scale(0.75) * pressProgress;

        if (text != null) {
            renderer.text(text, x + width / 2 - textWidth / 2, contentY, theme.textColor.get(), false);
        }
        else {
            double ts = theme.textHeight();
            renderer.quad(x + width / 2 - ts / 2, contentY, ts, ts, texture,
                MonocleStyle.mix(theme.textSecondaryColor.get(), theme.accentColor.get(), hoverProgress));
        }
    }
}
