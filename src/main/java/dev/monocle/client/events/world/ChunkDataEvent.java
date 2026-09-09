/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.world;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * @author Crosby
 * @implNote Shouldn't be put in a {@link dev.monocle.client.utils.misc.Pool} to avoid a race-condition, or in a {@link ThreadLocal} as it is shared between threads.
 */
public record ChunkDataEvent(LevelChunk chunk) {
}
