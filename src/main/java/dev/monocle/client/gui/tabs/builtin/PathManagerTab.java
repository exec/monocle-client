/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.tabs.builtin;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.tabs.Tab;
import dev.monocle.client.gui.tabs.TabScreen;
import dev.monocle.client.gui.tabs.WindowTabScreen;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.pathing.PathManagers;
import net.minecraft.client.gui.screens.Screen;

public class PathManagerTab extends Tab {
    public PathManagerTab() {
        super(PathManagers.get().getName());
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new PathManagerScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof PathManagerScreen;
    }

    private static class PathManagerScreen extends WindowTabScreen {
        public PathManagerScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);

            PathManagers.get().getSettings().get().onActivated();
        }

        @Override
        public void initWidgets() {
            WTextBox filter = add(theme.textBox("")).minWidth(400).expandX().widget();
            filter.setFocused(true);
            filter.action = () -> {
                clear();

                add(filter);
                add(theme.settings(PathManagers.get().getSettings().get(), filter.get().trim())).expandX();
            };

            add(theme.settings(PathManagers.get().getSettings().get(), filter.get().trim())).expandX();
        }

        @Override
        protected void onClosed() {
            PathManagers.get().getSettings().save();
        }
    }
}
