/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.misc.input;

import dev.monocle.client.gui.GuiKeyEvents;
import dev.monocle.client.mixin.KeyMappingAccessor;
import dev.monocle.client.utils.misc.CursorStyle;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;

import static dev.monocle.client.MonocleClient.mc;

public class Input {
    private static final boolean[] keys = new boolean[512];
    private static final boolean[] buttons = new boolean[16];

    private static CursorStyle lastCursorStyle = CursorStyle.Default;

    private Input() {
    }

    public static void setKeyState(int key, boolean pressed) {
        if (key >= 0 && key < keys.length) keys[key] = pressed;
    }

    public static void setButtonState(int button, boolean pressed) {
        if (button >= 0 && button < buttons.length) buttons[button] = pressed;
    }

    public static int getKey(KeyMapping bind) {
        return ((KeyMappingAccessor) bind).monocle$getKey().getValue();
    }

    public static void setKeyState(KeyMapping bind, boolean pressed) {
        setKeyState(getKey(bind), pressed);
    }

    public static boolean isPressed(KeyMapping bind) {
        return isKeyPressed(getKey(bind)) || isButtonPressed(getKey(bind));
    }

    public static boolean isKeyPressed(int key) {
        if (!GuiKeyEvents.canUseKeys) return false;

        if (key == InputConstants.UNKNOWN.getValue()) return false;
        return key < keys.length && keys[key];
    }

    public static boolean isButtonPressed(int button) {
        if (button == -1) return false;
        return button < buttons.length && buttons[button];
    }

    public static void setCursorStyle(CursorStyle style) {
        if (lastCursorStyle != style) {
            style.getCursor().select(mc.getWindow());
            lastCursorStyle = style;
        }
    }

    public static int getModifier(int key) {
        return switch (key) {
            case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> InputConstants.MOD_SHIFT;
            case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> InputConstants.MOD_CONTROL;
            case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> InputConstants.MOD_ALT;
            case InputConstants.KEY_LSUPER, InputConstants.KEY_RSUPER -> InputConstants.MOD_SUPER;
            default -> 0;
        };
    }
}
