/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.tabs;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.utils.Cell;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WWindow;

public abstract class WindowTabScreen extends TabScreen {
    private static final java.util.Map<String, Double> scrolls = new java.util.HashMap<>();
    protected final WWindow window;
    private final String scrollKey;
    private boolean restoredScroll;

    public WindowTabScreen(GuiTheme theme, Tab tab) {
        super(theme, tab);

        window = super.add(theme.window(tab.name)).center().widget();
        window.view.scrollOnlyWhenMouseOver = false;
        scrollKey = getClass().getName() + ":" + tab.getClass().getName();
        onClosed(() -> scrolls.put(scrollKey, window.view.scrollPosition() / theme.scale(1)));
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
