/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens.settings;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.screens.settings.base.CollectionListSettingScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;

import java.util.List;

public class ModuleListSettingScreen extends CollectionListSettingScreen<Module> {
    public ModuleListSettingScreen(GuiTheme theme, Setting<List<Module>> setting) {
        super(theme, "Select Modules", setting, setting.get(), Modules.get().getAll());
    }

    @Override
    protected WWidget getValueWidget(Module value) {
        return theme.label(value.title);
    }

    @Override
    protected String[] getValueNames(Module value) {
        String[] names = new String[value.aliases.length + 1];
        System.arraycopy(value.aliases, 0, names, 1, value.aliases.length);
        names[0] = value.title;
        return names;
    }
}
