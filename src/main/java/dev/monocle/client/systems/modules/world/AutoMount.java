/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.world;

//Created by squidoodly 16/07/2020

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.EntityTypeListSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.entity.EntityUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.animal.equine.ZombieHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Set;

public class AutoMount extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> checkSaddle = sgGeneral.add(new BoolSetting.Builder()
        .name("check-saddle")
        .description("Checks if the entity contains a saddle before mounting.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the entity you mount.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Rideable entities.")
        .filter(EntityUtils::isRideable)
        .build()
    );

    public AutoMount() {
        super(Categories.World, "auto-mount", "Automatically mounts entities.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player.isPassenger()) return;
        if (mc.player.isShiftKeyDown()) return;
        if (mc.player.getMainHandItem().getItem() instanceof SpawnEggItem) return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entities.get().contains(entity.getType())) continue;
            if (!PlayerUtils.isWithin(entity, 4)) continue;
            if ((entity instanceof Pig || entity instanceof SkeletonHorse || entity instanceof Strider || entity instanceof ZombieHorse) && !((Mob) entity).isSaddled())
                continue;
            if (!(entity instanceof Llama) && entity instanceof Mob mobEntity && checkSaddle.get() && !mobEntity.isSaddled())
                continue;
            interact(entity, rotate.get());
            return;
        }
    }

    private void interact(Entity entity, boolean rotate) {
        if (rotate) {
            Rotations.rotate(Rotations.getYaw(entity), Rotations.getPitch(entity), -100, () -> interact(entity));
        } else {
            interact(entity);
        }
    }

    private void interact(Entity entity) {
        EntityHitResult location = new EntityHitResult(entity, entity.getBoundingBox().getCenter());
        mc.gameMode.interact(mc.player, entity, location, InteractionHand.MAIN_HAND);
    }
}
