/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.notebot.instrumentdetect;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.core.BlockPos;

public interface InstrumentDetectFunction {
    /**
     * Detects an instrument for noteblock
     *
     * @param noteBlock Noteblock state
     * @param blockPos  Noteblock position
     * @return Detected instrument
     */
    NoteBlockInstrument detectInstrument(BlockState noteBlock, BlockPos blockPos);
}
