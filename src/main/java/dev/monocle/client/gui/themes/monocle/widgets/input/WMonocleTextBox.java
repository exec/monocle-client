/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle.widgets.input;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.themes.monocle.MonocleGuiTheme;
import dev.monocle.client.gui.themes.monocle.MonocleStyle;
import dev.monocle.client.gui.themes.monocle.MonocleWidget;
import dev.monocle.client.gui.themes.monocle.widgets.WMonocleLabel;
import dev.monocle.client.gui.utils.CharFilter;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WContainer;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.input.WTextBox;

public class WMonocleTextBox extends WTextBox implements MonocleWidget {
    private boolean cursorVisible;
    private double cursorTimer;

    private double animProgress;

    public WMonocleTextBox(String text, String placeholder, CharFilter filter, Class<? extends Renderer> renderer) {
        super(text, placeholder, filter, renderer);
    }

    @Override
    protected WContainer createCompletionsRootWidget() {
        return new WVerticalList() {
            @Override
            protected void onRender(GuiRenderer renderer1, double mouseX, double mouseY, double delta) {
                renderPanel(renderer1, x, y, width, height);
            }
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <T extends WWidget & ICompletionItem> T createCompletionsValueWidth(String completion, boolean selected) {
        return (T) new CompletionItem(completion, false, selected);
    }

    private static class CompletionItem extends WMonocleLabel implements ICompletionItem {
        private boolean selected;

        public CompletionItem(String text, boolean title, boolean selected) {
            super(text, title);
            this.selected = selected;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            if (selected) {
                MonocleStyle.rounded(renderer, x, y, width, height, theme.scale(2), MonocleStyle.alpha(theme().accentColor.get(), 0.18));
                renderer.quad(x, y, theme.scale(1), height, theme().accentColor.get());
            }
            super.onRender(renderer, mouseX, mouseY, delta);
        }

        @Override
        public boolean isSelected() {
            return selected;
        }

        @Override
        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public String getCompletion() {
            return text;
        }
    }

    @Override
    protected void onCursorChanged() {
        cursorVisible = true;
        cursorTimer = 0;
        animProgress = focused ? 1.0 : 0.0;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (cursorTimer >= 1) {
            cursorVisible = !cursorVisible;
            cursorTimer = 0;
        } else {
            cursorTimer += delta * 1.75;
        }

        renderInset(renderer, this, focused, mouseOver);

        MonocleGuiTheme theme = theme();
        double pad = pad();
        double overflowWidth = getOverflowWidthForRender();

        renderer.scissorStart(x + pad, y + pad, width - pad * 2, height - pad * 2);

        // Text content
        if (!text.isEmpty()) {
            this.renderer.render(renderer, x + pad - overflowWidth, y + pad, text, theme.textColor.get());
        } else if (placeholder != null) {
            this.renderer.render(renderer, x + pad - overflowWidth, y + pad, placeholder, theme.placeholderColor.get());
        }

        // Text highlighting
        if (focused && (cursor != selectionStart || cursor != selectionEnd)) {
            double selStart = x + pad + getTextWidth(selectionStart) - overflowWidth;
            double selEnd = x + pad + getTextWidth(selectionEnd) - overflowWidth;

            renderer.quad(selStart, y + pad, selEnd - selStart, theme.textHeight(), theme.textHighlightColor.get());
        }

        // Cursor
        animProgress = MonocleStyle.approach(animProgress, focused && cursorVisible ? 1 : 0, delta);

        if ((focused && cursorVisible) || animProgress > 0) {
            renderer.quad(x + pad + getTextWidth(cursor) - overflowWidth, y + pad, theme.scale(1), theme.textHeight(),
                MonocleStyle.alpha(theme.accentColor.get(), animProgress));
        }

        renderer.scissorEnd();
    }
}
