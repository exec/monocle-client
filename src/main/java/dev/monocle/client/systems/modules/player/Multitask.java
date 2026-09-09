/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;

public class Multitask extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> attackingEntities = sgGeneral.add(new BoolSetting.Builder()
        .name("attacking-entities")
        .description("Lets you attack entities while using an item.")
        .defaultValue(true)
        .build()
    );

    public Multitask() {
        super(Categories.Player, "multitask", "Lets you use items and attack at the same time.");
    }

    public boolean attackingEntities() {
        return isActive() && attackingEntities.get();
    }
}
