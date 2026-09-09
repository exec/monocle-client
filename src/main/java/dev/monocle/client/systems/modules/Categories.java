/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules;

import dev.monocle.client.addons.AddonManager;
import dev.monocle.client.addons.MonocleAddon;
import dev.monocle.client.utils.render.DisplayItemUtils;
import net.minecraft.world.item.Items;

public class Categories {
    public static final Category Combat = new Category("Combat", () -> DisplayItemUtils.toStack(Items.GOLDEN_SWORD));
    public static final Category Player = new Category("Player", () -> DisplayItemUtils.toStack(Items.ARMOR_STAND));
    public static final Category Movement = new Category("Movement", () -> DisplayItemUtils.toStack(Items.DIAMOND_BOOTS));
    public static final Category Render = new Category("Render", () -> DisplayItemUtils.toStack(Items.GLASS));
    public static final Category World = new Category("World", () -> DisplayItemUtils.toStack(Items.GRASS_BLOCK));
    public static final Category Misc = new Category("Misc", () -> DisplayItemUtils.toStack(Items.LAVA_BUCKET));

    public static boolean REGISTERING;

    public static void init() {
        REGISTERING = true;

        // Monocle
        Modules.registerCategory(Combat);
        Modules.registerCategory(Player);
        Modules.registerCategory(Movement);
        Modules.registerCategory(Render);
        Modules.registerCategory(World);
        Modules.registerCategory(Misc);

        // Addons
        AddonManager.ADDONS.forEach(MonocleAddon::onRegisterCategories);

        REGISTERING = false;
    }
}
