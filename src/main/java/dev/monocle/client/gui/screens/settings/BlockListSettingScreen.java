/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens.settings;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.screens.settings.base.CollectionListSettingScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.settings.BlockListSetting;
import dev.monocle.client.utils.misc.Names;
import dev.monocle.client.utils.render.DisplayItemUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Predicate;

public class BlockListSettingScreen extends CollectionListSettingScreen<Block> {
    public BlockListSettingScreen(GuiTheme theme, BlockListSetting setting) {
        super(theme, "Select Blocks", setting, setting.get(), BuiltInRegistries.BLOCK);
    }

    @Override
    protected boolean includeValue(Block value) {
        if (BuiltInRegistries.BLOCK.getKey(value).getPath().endsWith("_wall_banner")) {
            return false;
        }

        Predicate<Block> filter = ((BlockListSetting) setting).filter;

        if (filter == null) return value != Blocks.AIR;
        return filter.test(value);
    }

    @Override
    protected WWidget getValueWidget(Block value) {
        return theme.itemWithLabel(DisplayItemUtils.toStack(value), Names.get(value));
    }

    @Override
    protected String[] getValueNames(Block value) {
        return new String[]{
            Names.get(value),
            BuiltInRegistries.BLOCK.getKey(value).toString()
        };
    }

    @Override
    protected Block getAdditionalValue(Block value) {
        String path = BuiltInRegistries.BLOCK.getKey(value).getPath();
        if (!path.endsWith("_banner")) return null;

        return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(path.substring(0, path.length() - 6) + "wall_banner"));
    }
}
