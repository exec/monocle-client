/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui;

import dev.monocle.client.gui.utils.Cell;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WWindow;

public abstract class WindowScreen extends WidgetScreen {
    private static final java.util.Map<String, Double> scrolls = new java.util.HashMap<>();
    protected final WWindow window;
    private final String scrollKey;
    private boolean restoredScroll;

    public WindowScreen(GuiTheme theme, WWidget icon, String title) {
        super(theme, title);

        window = super.add(theme.window(icon, title)).center().widget();
        window.view.scrollOnlyWhenMouseOver = false;
        scrollKey = getClass().getName() + ":" + title;
        onClosed(() -> scrolls.put(scrollKey, window.view.scrollPosition() / theme.scale(1)));
    }

    public WindowScreen(GuiTheme theme, String title) {
        this(theme, null, title);
    }

    @Override protected void init() {
        super.init();
        if (!restoredScroll) {
            restoredScroll = true;
            window.view.restoreScroll(theme.scale(scrolls.getOrDefault(scrollKey, 0.0)));
        }
    }

    @Override
    public <W extends WWidget> Cell<W> add(W widget) {
        return window.add(widget);
    }

    @Override
    public void clear() {
        window.clear();
    }
}
