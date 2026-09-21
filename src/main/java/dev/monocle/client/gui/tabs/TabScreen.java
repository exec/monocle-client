/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.tabs;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.gui.utils.Cell;
import dev.monocle.client.gui.widgets.WWidget;

public abstract class TabScreen extends WidgetScreen {
    public final Tab tab;
    public dev.monocle.client.gui.widgets.WTopBar navigation;

    public TabScreen(GuiTheme theme, Tab tab) {
        super(theme, tab.name);

        this.tab = tab;
    }

    public <T extends WWidget> Cell<T> addDirect(T widget) {
        return super.add(widget);
    }

    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
        if (!locked && (key.modifiers() & com.mojang.blaze3d.platform.InputConstants.MOD_CONTROL) != 0
            && key.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB) {
            var tabs = Tabs.navigation();
            int current = tabs.indexOf(Tabs.secondary(tab) ? Tabs.get(dev.monocle.client.gui.tabs.builtin.SettingsTab.class) : tab);
            int step = (key.modifiers() & com.mojang.blaze3d.platform.InputConstants.MOD_SHIFT) != 0 ? -1 : 1;
            tabs.get(Math.floorMod(current + step, tabs.size())).openScreen(theme);
            return true;
        }
        return super.keyPressed(key);
    }
}
