/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.WHorizontalSeparator;

public class WMonocleHorizontalSeparator extends WHorizontalSeparator implements MonocleWidget {
    public WMonocleHorizontalSeparator(String text) {
        super(text);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (text == null) renderWithoutText(renderer);
        else renderWithText(renderer);
    }

    private void renderWithoutText(GuiRenderer renderer) {
        MonocleGuiTheme theme = theme();
        double s = theme.scale(1);
        double w = width / 2;

        renderer.quad(x, y + s, w, s, theme.separatorEdges.get(), theme.separatorCenter.get());
        renderer.quad(x + w, y + s, w, s, theme.separatorCenter.get(), theme.separatorEdges.get());
        if (width >= s * 6) {
            renderer.quad(x + w - s * 2, y + s, s * 4, s, MonocleStyle.alpha(theme.accentColor.get(), 0.6));
        }
    }

    private void renderWithText(GuiRenderer renderer) {
        MonocleGuiTheme theme = theme();
        double s = theme.scale(2);
        double h = theme.scale(1);

        double textStart = Math.round(width / 2.0 - textWidth / 2.0 - s);
        double textEnd = s + textStart + textWidth + s;

        double offsetY = Math.round(height / 2.0);

        if (textStart > 0) {
            renderer.quad(x, y + offsetY, textStart, h, theme.separatorEdges.get(), theme.separatorCenter.get());
        }
        MonocleStyle.rounded(renderer, x + textStart, y, textWidth + s * 2, height, s,
            MonocleStyle.alpha(theme.backgroundColor.get(false, true), 0.6));
        renderer.text(text, x + textStart + s, y, theme.separatorText.get(), false);
        if (width > textEnd) {
            renderer.quad(x + textEnd, y + offsetY, width - textEnd, h, theme.separatorCenter.get(), theme.separatorEdges.get());
        }
    }
}
