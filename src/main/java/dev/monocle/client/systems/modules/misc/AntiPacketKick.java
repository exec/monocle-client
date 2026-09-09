/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.misc;

import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;

public class AntiPacketKick extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> catchExceptions = sgGeneral.add(new BoolSetting.Builder()
        .name("catch-exceptions")
        .description("Drops corrupted packets.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> logExceptions = sgGeneral.add(new BoolSetting.Builder()
        .name("log-exceptions")
        .description("Logs caught exceptions.")
        .defaultValue(true)
        .visible(catchExceptions::get)
        .build()
    );

    public AntiPacketKick() {
        super(Categories.Misc, "anti-packet-kick", "Attempts to prevent you from being disconnected by large packets.");
    }

    public boolean catchExceptions() {
        return isActive() && catchExceptions.get();
    }
}
