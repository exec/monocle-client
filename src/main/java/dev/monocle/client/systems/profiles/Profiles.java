/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.profiles;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.game.GameJoinedEvent;
import dev.monocle.client.systems.System;
import dev.monocle.client.systems.Systems;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.misc.NbtUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Profiles extends System<Profiles> implements Iterable<Profile> {
    public static final File FOLDER = new File(MonocleClient.FOLDER, "profiles");

    private List<Profile> profiles = new ArrayList<>();

    public Profiles() {
        super("profiles");
    }

    public static Profiles get() {
        return Systems.get(Profiles.class);
    }

    public void add(Profile profile) {
        if (!profiles.contains(profile)) profiles.add(profile);
        profile.save();
        save();
    }

    public void remove(Profile profile) {
        if (profiles.remove(profile)) profile.delete();
        save();
    }

    public Profile get(String name) {
        for (Profile profile : this) {
            if (profile.name.get().equalsIgnoreCase(name)) {
                return profile;
            }
        }

        return null;
    }

    public List<Profile> getAll() {
        return profiles;
    }

    @Override
    public File getFile() {
        return new File(FOLDER, "profiles.nbt");
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        for (Profile profile : this) {
            if (profile.loadOnJoin.get().contains(Utils.getWorldName())) {
                profile.load();
            }
        }
    }

    public boolean isEmpty() {
        return profiles.isEmpty();
    }

    @Override
    public @NonNull Iterator<Profile> iterator() {
        return profiles.iterator();
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("profiles", NbtUtils.listToTag(profiles));
        return tag;
    }

    @Override
    public Profiles fromTag(CompoundTag tag) {
        profiles = NbtUtils.listFromTag(tag.getListOrEmpty("profiles"), Profile::new);

        for (File file : FOLDER.listFiles()) {
            if (file.isDirectory() && get(file.getName()) == null) {
                Profile p = new Profile();
                p.name.set(file.getName());

                boolean add = false;
                for (File f : file.listFiles()) {
                    if (f.getName().equals("hud.nbt")) p.hud.set(add = true);
                    else if (f.getName().equals("macros.nbt")) p.macros.set(add = true);
                    else if (f.getName().equals("modules.nbt")) p.modules.set(add = true);
                    else if (f.getName().endsWith(".nbt")) p.waypoints.set(add = true);
                }

                if (add) add(p);
            }
        }

        return this;
    }
}
