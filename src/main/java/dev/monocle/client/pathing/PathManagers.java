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
    private static final PauseOwners pauseOwners=new PauseOwners();

    public static IPathManager get() {
        return INSTANCE;
    }
    public static boolean hasNavigation(){return pauseOwners.held()||BaritoneUtils.movementBusy();}
    public static void pause(Object owner){pauseOwners.pause(INSTANCE,owner);}
    public static void resume(Object owner){pauseOwners.resume(INSTANCE,owner);}
    static final class PauseOwners {
        private final java.util.Set<Object> owners=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean held(){return !owners.isEmpty();}
        void pause(IPathManager manager,Object owner){
            java.util.Objects.requireNonNull(owner);
            if(owners.add(owner)&&owners.size()==1)manager.pause();
        }
        void resume(IPathManager manager,Object owner){
            if(owners.remove(owner)&&owners.isEmpty())manager.resume();
        }
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
