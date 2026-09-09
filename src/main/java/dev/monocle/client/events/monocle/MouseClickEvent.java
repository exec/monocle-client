/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.monocle;

import dev.monocle.client.events.Cancellable;
import dev.monocle.client.utils.misc.input.KeyAction;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

public class MouseClickEvent extends Cancellable {
    private static final MouseClickEvent INSTANCE = new MouseClickEvent();

    public MouseButtonEvent click;
    public MouseButtonInfo input;
    public KeyAction action;

    public static MouseClickEvent get(MouseButtonEvent click, KeyAction action) {
        INSTANCE.setCancelled(false);
        INSTANCE.click = click;
        INSTANCE.input = click.buttonInfo();
        INSTANCE.action = action;
        return INSTANCE;
    }

    public int button() {
        return INSTANCE.input.button();
    }
}
