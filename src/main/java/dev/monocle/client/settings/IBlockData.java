/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.settings;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.utils.misc.IChangeable;
import dev.monocle.client.utils.misc.ICopyable;
import dev.monocle.client.utils.misc.ISerializable;
import net.minecraft.world.level.block.Block;

public interface IBlockData<T extends ICopyable<T> & ISerializable<T> & IChangeable & IBlockData<T>> {
    WidgetScreen createScreen(GuiTheme theme, Block block, BlockDataSetting<T> setting);
}
