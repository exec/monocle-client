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
import dev.monocle.client.gui.widgets.pressable.WConfirmedButton;
import dev.monocle.client.utils.render.color.Color;

public class WMonocleConfirmedButton extends WConfirmedButton implements MonocleWidget {
    private double hoverProgress;
    private double pressProgress;

    public WMonocleConfirmedButton(String text, String confirmText, GuiTexture texture) {
        super(text, confirmText, texture);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MonocleGuiTheme theme = theme();
        double pad = pad();

        if (disabled) pressedOnce = false;
        hoverProgress = MonocleStyle.approach(hoverProgress, !disabled && mouseOver && !theme.disableHoverColor ? 1 : 0, delta);
        pressProgress = MonocleStyle.approach(pressProgress, !disabled && pressed ? 1 : 0, delta);
        double intensity = Math.max(hoverProgress, pressProgress);
        Color outline = pressedOnce ? theme.accentColor.get()
            : MonocleStyle.mix(theme.outlineColor.get(), theme.outlineColor.get(pressed, true), intensity);
        Color fg = disabled ? theme.textSecondaryColor.get() : pressedOnce ? theme.backgroundColor.get() : theme.textColor.get();
        Color bg = pressedOnce ? theme.accentColor.get()
            : MonocleStyle.mix(theme.backgroundColor.get(), theme.backgroundColor.get(pressed, true), intensity);

        renderBackground(renderer, this, outline, bg);

        String text = getText();
        double contentY = y + pad + theme.scale(0.75) * pressProgress;

        if (text != null) {
            renderer.text(text, x + width / 2 - textWidth / 2, contentY, fg, false);
        } else {
            double ts = theme.textHeight();
            renderer.quad(x + width / 2 - ts / 2, contentY, ts, ts, texture, fg);
        }
    }
}
