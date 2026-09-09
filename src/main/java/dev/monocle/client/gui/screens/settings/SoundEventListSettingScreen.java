/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens.settings;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.screens.settings.base.CollectionListSettingScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.settings.Setting;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

import java.util.List;

public class SoundEventListSettingScreen extends CollectionListSettingScreen<SoundEvent> {
    public SoundEventListSettingScreen(GuiTheme theme, Setting<List<SoundEvent>> setting) {
        super(theme, "Select Sounds", setting, setting.get(), BuiltInRegistries.SOUND_EVENT);
    }

    @Override
    protected WWidget getValueWidget(SoundEvent value) {
        return theme.label(value.location().getPath());
    }

    @Override
    protected String[] getValueNames(SoundEvent value) {
        return new String[]{
            value.location().toString(),
            I18n.get("subtitles." + value.location().getPath())
        };
    }
}
