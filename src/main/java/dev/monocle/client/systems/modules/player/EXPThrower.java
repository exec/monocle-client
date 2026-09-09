/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.Items;

public class EXPThrower extends Module {
    public EXPThrower() {
        super(Categories.Player, "exp-thrower", "Automatically throws XP bottles from your hotbar.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        FindItemResult exp = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (!exp.found()) return;

        Rotations.rotate(mc.player.getYRot(), 90, () -> {
            if (exp.getHand() != null) {
                mc.gameMode.useItem(mc.player, exp.getHand());
            } else {
                InvUtils.swap(exp.slot(), true);
                mc.gameMode.useItem(mc.player, exp.getHand());
                InvUtils.swapBack();
            }
        });
    }
}
