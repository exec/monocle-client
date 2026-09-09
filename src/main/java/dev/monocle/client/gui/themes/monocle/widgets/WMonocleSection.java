/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WSection;
import dev.monocle.client.gui.widgets.pressable.WTriangle;

public class WMonocleSection extends WSection {
    public WMonocleSection(String title, boolean expanded, WWidget headerWidget) {
        super(title, expanded, headerWidget);
    }

    @Override
    protected WHeader createHeader() {
        return new WMonocleHeader(title);
    }

    protected class WMonocleHeader extends WHeader implements MonocleWidget {
        private WTriangle triangle;
        private double hoverProgress;

        public WMonocleHeader(String title) {
            super(title);
        }

        @Override
        public void init() {
            add(theme.horizontalSeparator(title)).expandX();

            if (headerWidget != null) add(headerWidget);

            triangle = new WHeaderTriangle();
            triangle.theme = theme;
            triangle.action = this::onClick;

            add(triangle);
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            hoverProgress = MonocleStyle.approach(hoverProgress, mouseOver && !theme.disableHoverColor ? 1 : 0, delta);
            MonocleStyle.rounded(renderer, x, y, width, height, theme.scale(3),
                MonocleStyle.alpha(theme().backgroundColor.get(false, true), hoverProgress * 0.6));
            triangle.rotation = (1 - animProgress) * -90;
        }
    }

    protected static class WHeaderTriangle extends WTriangle implements MonocleWidget {
        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            renderer.rotatedQuad(x + width * 0.25, y + height * 0.25, width * 0.5, height * 0.5, rotation,
                GuiRenderer.TRIANGLE, mouseOver && !theme.disableHoverColor ? theme().textColor.get() : theme().accentColor.get());
        }
    }
}
