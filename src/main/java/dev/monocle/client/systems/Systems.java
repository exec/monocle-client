/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.systems.accounts.Accounts;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.systems.hud.Hud;
import dev.monocle.client.systems.macros.Macros;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.profiles.Profiles;
import dev.monocle.client.systems.proxies.Proxies;
import dev.monocle.client.systems.waypoints.Waypoints;
import meteordevelopment.orbit.EventHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Systems {
    @SuppressWarnings("rawtypes")
    private static final Map<Class<? extends System>, System<?>> systems = new Reference2ReferenceOpenHashMap<>();
    private static final List<Runnable> preLoadTasks = new ArrayList<>(1);

    public static void addPreLoadTask(Runnable task) {
        preLoadTasks.add(task);
    }

    public static void init() {
        // Has to be loaded first so the hidden modules list in config tab can load modules
        add(new Modules());

        Config config = new Config();
        System<?> configSystem = add(config);
        configSystem.init();
        configSystem.load();

        // Registers the colors from config tab. This allows rainbow colours to work for friends.
        config.settings.registerColorSettings(null);

        add(new Macros());
        add(new Friends());
        add(new Accounts());
        add(new Waypoints());
        add(new Profiles());
        add(new Proxies());
        add(new Hud());

        MonocleClient.EVENT_BUS.subscribe(Systems.class);
    }

    public static System<?> add(System<?> system) {
        systems.put(system.getClass(), system);
        MonocleClient.EVENT_BUS.subscribe(system);
        system.init();

        return system;
    }

    // save/load

    @EventHandler
    private static void onGameLeft(GameLeftEvent event) {
        save();
    }

    public static void save(File folder) {
        long start = java.lang.System.currentTimeMillis();
        MonocleClient.LOG.info("Saving");

        for (System<?> system : systems.values()) system.save(folder);

        MonocleClient.LOG.info("Saved in {} milliseconds.", java.lang.System.currentTimeMillis() - start);
    }

    public static void save() {
        save(null);
    }

    public static void load(File folder) {
        long start = java.lang.System.currentTimeMillis();
        MonocleClient.LOG.info("Loading");

        for (Runnable task : preLoadTasks) task.run();
        for (System<?> system : systems.values()) system.load(folder);

        MonocleClient.LOG.info("Loaded in {} milliseconds", java.lang.System.currentTimeMillis() - start);
    }

    public static void load() {
        load(null);
    }

    @SuppressWarnings("unchecked")
    public static <T extends System<?>> T get(Class<T> klass) {
        return (T) systems.get(klass);
    }
}
