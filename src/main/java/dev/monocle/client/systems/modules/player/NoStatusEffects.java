/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.settings.StatusEffectListSetting;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import net.minecraft.world.effect.MobEffect;

import java.util.List;

import static net.minecraft.world.effect.MobEffects.*;

public class NoStatusEffects extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<MobEffect>> blockedEffects = sgGeneral.add(new StatusEffectListSetting.Builder()
        .name("blocked-effects")
        .description("Effects to block.")
        .defaultValue(
            LEVITATION.value(),
            JUMP_BOOST.value(),
            SLOW_FALLING.value(),
            DOLPHINS_GRACE.value()
        )
        .build()
    );

    public NoStatusEffects() {
        super(Categories.Player, "no-status-effects", "Blocks specified status effects.");
    }

    public boolean shouldBlock(MobEffect effect) {
        return isActive() && blockedEffects.get().contains(effect);
    }
}
