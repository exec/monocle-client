/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens.settings;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.screens.settings.base.DynamicRegistryListSettingScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.utils.misc.Names;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Set;

public class EnchantmentListSettingScreen extends DynamicRegistryListSettingScreen<Enchantment> {
    public EnchantmentListSettingScreen(GuiTheme theme, Setting<Set<ResourceKey<Enchantment>>> setting) {
        super(theme, "Select Enchantments", setting, setting.get(), Registries.ENCHANTMENT);
    }

    @Override
    protected WWidget getValueWidget(ResourceKey<Enchantment> value) {
        return theme.label(Names.get(value));
    }

    @Override
    protected String[] getValueNames(ResourceKey<Enchantment> value) {
        return new String[]{
            Names.get(value),
            value.identifier().toString()
        };
    }
}
