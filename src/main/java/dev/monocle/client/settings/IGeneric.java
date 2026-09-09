/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.settings;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.utils.misc.ICopyable;
import dev.monocle.client.utils.misc.ISerializable;

public interface IGeneric<T extends IGeneric<T>> extends ICopyable<T>, ISerializable<T> {
    WidgetScreen createScreen(GuiTheme theme, GenericSetting<T> setting);
}
