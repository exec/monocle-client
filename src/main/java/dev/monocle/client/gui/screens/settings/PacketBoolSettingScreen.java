/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens.settings;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.screens.settings.base.CollectionListSettingScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.settings.PacketListSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.utils.network.PacketUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.jspecify.annotations.NonNull;

import java.util.Set;
import java.util.function.Predicate;

public class PacketBoolSettingScreen extends CollectionListSettingScreen<PacketType<? extends @NonNull Packet<?>>> {
    public PacketBoolSettingScreen(GuiTheme theme, Setting<Set<PacketType<? extends @NonNull Packet<?>>>> setting) {
        super(theme, "Select Packets", setting, setting.get(), PacketUtils.getPackets());
    }

    @Override
    protected boolean includeValue(PacketType<? extends @NonNull Packet<?>> value) {
        Predicate<PacketType<? extends @NonNull Packet<?>>> filter = ((PacketListSetting) setting).filter;

        if (filter == null) return true;
        return filter.test(value);
    }

    @Override
    protected WWidget getValueWidget(PacketType<? extends @NonNull Packet<?>> value) {
        return theme.label(value.toString());
    }

    @Override
    protected String[] getValueNames(PacketType<? extends @NonNull Packet<?>> value) {
        return new String[]{
            value.toString()
        };
    }
}
