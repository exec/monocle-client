/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.utils.AlignmentX;
import dev.monocle.client.gui.widgets.pressable.WPressable;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.render.color.Color;

import static dev.monocle.client.MonocleClient.mc;
import static com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
import static com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

public class WMonocleModule extends WPressable implements MonocleWidget {
    private final Module module;
    private final String title;

    private double titleWidth;

    private double hoverProgress;
    private double activeProgress;

    public WMonocleModule(Module module, String title) {
        this.module = module;
        this.title = title;
        this.tooltip = module.description;

        activeProgress = module.isActive() ? 1 : 0;
    }

    @Override
    public double pad() {
        return theme.scale(4);
    }

    @Override
    protected void onCalculateSize() {
        double pad = pad();

        if (titleWidth == 0) titleWidth = theme.textWidth(title);

        width = pad + titleWidth + pad;
        height = pad + theme.textHeight() + pad;
    }

    @Override
    protected void onPressed(int button) {
        if (button == MOUSE_BUTTON_LEFT) module.toggle();
        else if (button == MOUSE_BUTTON_RIGHT) mc.gui.setScreen(theme.moduleScreen(module));
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MonocleGuiTheme theme = theme();
        double pad = pad();

        hoverProgress = MonocleStyle.approach(hoverProgress, mouseOver && !theme.disableHoverColor ? 1 : 0, delta);
        activeProgress = MonocleStyle.approach(activeProgress, module.isActive() ? 1 : 0, delta);
        double s = theme.scale(1);
        Color surface = MonocleStyle.mix(theme.backgroundColor.get(), theme.moduleBackground.get(), activeProgress);
        surface = MonocleStyle.mix(surface, theme.accentColor.get(), hoverProgress * 0.07);
        MonocleStyle.rounded(renderer, x + s, y + s, width - s * 2, height - s * 2, theme.scale(3),
            MonocleStyle.alpha(surface, 0.3 + activeProgress * 0.55 + hoverProgress * 0.15));

        if (hoverProgress > 0) {
            renderer.quad(x + pad, y + s, width - pad * 2, s,
                MonocleStyle.alpha(theme.accentColor.get(), hoverProgress * 0.3));
        }
        if (activeProgress > 0) {
            MonocleStyle.rounded(renderer, x + s, y + height * 0.2, s * 3, height * 0.6, s,
                MonocleStyle.alpha(theme.checkboxColor.get(), activeProgress * 0.15));
            MonocleStyle.rounded(renderer, x + s, y + height * 0.25, s * 2, height * 0.5, s,
                MonocleStyle.alpha(theme.checkboxColor.get(), activeProgress));
        }

        double x = this.x + pad;
        double w = width - pad * 2;

        if (theme.moduleAlignment.get() == AlignmentX.Center) {
            x += w / 2 - titleWidth / 2;
        } else if (theme.moduleAlignment.get() == AlignmentX.Right) {
            x += w - titleWidth;
        }

        renderer.text(title, x, y + pad, theme.textColor.get(), false);
    }
}
