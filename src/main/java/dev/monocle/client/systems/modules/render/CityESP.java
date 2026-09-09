/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.render;

import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.settings.ColorSetting;
import dev.monocle.client.settings.EnumSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.entity.EntityUtils;
import dev.monocle.client.utils.entity.SortPriority;
import dev.monocle.client.utils.entity.TargetUtils;
import dev.monocle.client.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

public class CityESP extends Module {
    private final SettingGroup sgRender = settings.createGroup("Render");

    // Render

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the rendering.")
        .defaultValue(new SettingColor(225, 0, 0, 75))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the rendering.")
        .defaultValue(new SettingColor(225, 0, 0, 255))
        .build()
    );

    private BlockPos target;

    public CityESP() {
        super(Categories.Render, "city-esp", "Displays blocks that can be broken in order to city another player.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Player targetEntity = TargetUtils.getPlayerTarget(mc.player.blockInteractionRange() + 2, SortPriority.LowestDistance);

        if (TargetUtils.isBadTarget(targetEntity, mc.player.blockInteractionRange() + 2)) {
            target = null;
        } else {
            target = EntityUtils.getCityBlock(targetEntity);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (target == null) return;

        event.renderer.box(target, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}
