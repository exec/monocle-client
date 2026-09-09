/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.pathing;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.utils.PreInit;

import java.lang.reflect.InvocationTargetException;

public class PathManagers {
    private static IPathManager INSTANCE = new NopPathManager();

    public static IPathManager get() {
        return INSTANCE;
    }

    @PreInit
    public static void init() {
        if (exists("meteordevelopment.voyager.PathManager")) {
            try {
                INSTANCE = (IPathManager) Class.forName("meteordevelopment.voyager.PathManager").getConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                     NoSuchMethodException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        if (exists("baritone.api.BaritoneAPI")) {
            BaritoneUtils.IS_AVAILABLE = true;

            if (INSTANCE instanceof NopPathManager)
                INSTANCE = new BaritonePathManager();
        }

        MonocleClient.LOG.info("Path Manager: {}", INSTANCE.getName());
    }

    private static boolean exists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }
}
