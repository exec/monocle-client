/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.addons;

import dev.monocle.client.MonocleClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;

import java.util.ArrayList;
import java.util.List;

public class AddonManager {
    public static final List<MonocleAddon> ADDONS = new ArrayList<>();

    public static void init() {
        // Monocle pseudo addon
        {
            MonocleClient.ADDON = new MonocleAddon() {
                @Override
                public void onInitialize() {}

                @Override
                public String getPackage() {
                    return "dev.monocle.client";
                }

                @Override
                public String getWebsite() {
                    return null;
                }

                @Override
                public GithubRepo getRepo() {
                    return null;
                }

                @Override
                public String getCommit() {
                    String commit = MonocleClient.MOD_META.getCustomValue(MonocleClient.MOD_ID + ":commit").getAsString();
                    return commit.isEmpty() ? null : commit;
                }
            };

            ModMetadata metadata = FabricLoader.getInstance().getModContainer(MonocleClient.MOD_ID).get().getMetadata();

            MonocleClient.ADDON.name = metadata.getName();
            MonocleClient.ADDON.authors = new String[metadata.getAuthors().size()];
            if (metadata.containsCustomValue(MonocleClient.MOD_ID + ":color")) {
                MonocleClient.ADDON.color.parse(metadata.getCustomValue(MonocleClient.MOD_ID + ":color").getAsString());
            }

            int i = 0;
            for (Person author : metadata.getAuthors()) {
                MonocleClient.ADDON.authors[i++] = author.getName();
            }

            ADDONS.add(MonocleClient.ADDON);
        }

        // Addons
        for (EntrypointContainer<MonocleAddon> entrypoint : FabricLoader.getInstance().getEntrypointContainers("monocle", MonocleAddon.class)) {
            ModMetadata metadata = entrypoint.getProvider().getMetadata();
            MonocleAddon addon;
            try {
                addon = entrypoint.getEntrypoint();
            } catch (Throwable throwable) {
                throw new RuntimeException("Exception during addon init \"%s\".".formatted(metadata.getName()), throwable);
            }

            addon.name = metadata.getName();

            if (metadata.getAuthors().isEmpty()) throw new RuntimeException("Addon \"%s\" requires at least 1 author to be defined in it's fabric.mod.json. See https://fabricmc.net/wiki/documentation:fabric_mod_json_spec".formatted(addon.name));
            addon.authors = new String[metadata.getAuthors().size()];

            if (metadata.containsCustomValue(MonocleClient.MOD_ID + ":color")) {
                addon.color.parse(metadata.getCustomValue(MonocleClient.MOD_ID + ":color").getAsString());
            }

            int i = 0;
            for (Person author : metadata.getAuthors()) {
                addon.authors[i++] = author.getName();
            }

            ADDONS.add(addon);
        }
    }
}
