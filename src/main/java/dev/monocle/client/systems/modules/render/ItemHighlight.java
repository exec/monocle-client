/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.render;

import dev.monocle.client.settings.ColorSetting;
import dev.monocle.client.settings.ItemListSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.render.color.SettingColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class ItemHighlight extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items to highlight.")
        .build()
    );

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("The color to highlight the items with.")
        .defaultValue(new SettingColor(225, 25, 255, 50))
        .build()
    );

    public ItemHighlight() {
        super(Categories.Render, "item-highlight", "Highlights selected items when in guis");
    }

    public int getColor(ItemStack stack) {
        if (stack != null && items.get().contains(stack.getItem()) && isActive()) return color.get().getPacked();
        return -1;
    }
}
