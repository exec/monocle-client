/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;
import java.util.LinkedHashMap;
import java.util.Map;

public class BaritoneUtils {
    public static boolean IS_AVAILABLE = false;

    private BaritoneUtils() {
    }

    /** A read-only scan lease; cancellation never stops an unrelated replacement goal. */
    public static final class StashNavigation implements AutoCloseable {
        private final Map<baritone.api.Settings.Setting<?>, Object> previous = new LinkedHashMap<>();
        private Goal goal;
        public StashNavigation() {
            var settings = BaritoneAPI.getSettings();
            forbid(settings.allowBreak); forbid(settings.allowPlace); forbid(settings.allowInventory);
            previous.put(settings.allowBreakAnyway, settings.allowBreakAnyway.value);
            settings.allowBreakAnyway.value = java.util.List.of();
        }
        private void forbid(baritone.api.Settings.Setting<Boolean> setting) {
            previous.put(setting, setting.value); setting.value = false;
        }
        public void moveTo(BlockPos target, int mode) {
            var process = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
            if (goal != null && process.getGoal() != goal) throw new IllegalStateException("Another Baritone task took control; stash scan stopped");
            if (goal == null) { goal = mode==0?new GoalBlock(target):mode>0?new GoalNear(target,mode):new GoalGetToBlock(target); process.setGoalAndPath(goal); }
        }
        @Override @SuppressWarnings({"rawtypes", "unchecked"}) public void close() {
            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            try {
                if (goal != null && baritone.getCustomGoalProcess().getGoal() == goal) baritone.getPathingBehavior().cancelEverything();
            } finally {
                previous.forEach((setting, value) -> {
                    if (Boolean.FALSE.equals(setting.value) || setting.value instanceof java.util.List<?> list && list.isEmpty())
                        ((baritone.api.Settings.Setting) setting).value = value;
                });
                previous.clear(); goal = null;
            }
        }
    }

    public static String getPrefix() {
        if (IS_AVAILABLE) {
            return BaritoneAPI.getSettings().prefix.value;
        }

        return "";
    }
}
