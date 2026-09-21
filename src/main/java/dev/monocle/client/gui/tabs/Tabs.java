/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.tabs;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import dev.monocle.client.gui.tabs.builtin.*;
import dev.monocle.client.pathing.PathManagers;
import dev.monocle.client.utils.PreInit;

import java.util.ArrayList;
import java.util.List;

public class Tabs {
    private static final List<Tab> tabs = new ArrayList<>();
    private static final Reference2ReferenceOpenHashMap<Class<? extends Tab>, Tab> tabInstances = new Reference2ReferenceOpenHashMap<>();

    private Tabs() {
    }

    @PreInit(dependencies = PathManagers.class)
    public static void init() {
        add(new ModulesTab());
        add(new BotsTab());
        add(new WorkflowsTab());
        add(new StashesTab());
        add(new HudTab());
        add(new ProfilesTab());
        add(new SettingsTab());
        add(new ConfigTab());
        add(new GuiTab());
        add(new FriendsTab());
        add(new MacrosTab());

        if (PathManagers.get().getSettings().get().sizeGroups() > 0) {
            add(new PathManagerTab());
        }
    }

    public static void add(Tab tab) {
        tabs.add(tab);
        tabInstances.put(tab.getClass(), tab);
    }

    public static List<Tab> get() {
        return tabs;
    }

    /** Keep secondary destinations registered for shortcuts and addons. */
    public static boolean secondary(Tab tab) {
        return tab instanceof ConfigTab || tab instanceof GuiTab || tab instanceof FriendsTab
            || tab instanceof MacrosTab || tab instanceof PathManagerTab;
    }

    public static List<Tab> navigation() {
        return tabs.stream().filter(tab -> !secondary(tab)).toList();
    }

    public static Tab get(Class<? extends Tab> klass) {
        return tabInstances.get(klass);
    }
}
