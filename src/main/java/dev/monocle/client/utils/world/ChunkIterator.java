/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.world;

import dev.monocle.client.mixin.ClientChunkCacheAccessor;
import dev.monocle.client.mixin.ClientChunkMapAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Iterator;

import static dev.monocle.client.MonocleClient.mc;

public class ChunkIterator implements Iterator<ChunkAccess> {
    private final ClientChunkMapAccessor map = (ClientChunkMapAccessor) (Object) ((ClientChunkCacheAccessor) mc.level.getChunkSource()).monocle$getStorage();
    private final boolean onlyWithLoadedNeighbours;

    private int i = 0;
    private ChunkAccess chunk;

    public ChunkIterator(boolean onlyWithLoadedNeighbours) {
        this.onlyWithLoadedNeighbours = onlyWithLoadedNeighbours;

        getNext();
    }

    private ChunkAccess getNext() {
        ChunkAccess prev = chunk;
        chunk = null;

        while (i < map.monocle$getChunks().length()) {
            chunk = map.monocle$getChunks().get(i++);
            if (chunk != null && (!onlyWithLoadedNeighbours || isInRadius(chunk))) break;
        }

        return prev;
    }

    private boolean isInRadius(ChunkAccess chunk) {
        int x = chunk.getPos().x();
        int z = chunk.getPos().z();

        return mc.level.getChunkSource().hasChunk(x + 1, z) && mc.level.getChunkSource().hasChunk(x - 1, z) && mc.level.getChunkSource().hasChunk(x, z + 1) && mc.level.getChunkSource().hasChunk(x, z - 1);
    }

    @Override
    public boolean hasNext() {
        return chunk != null;
    }

    @Override
    public ChunkAccess next() {
        return getNext();
    }
}
