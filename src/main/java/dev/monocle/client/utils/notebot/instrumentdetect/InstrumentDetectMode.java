/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.notebot.instrumentdetect;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.NoteBlock;

public enum InstrumentDetectMode {
    BlockState(((noteBlock, _) -> noteBlock.getValue(NoteBlock.INSTRUMENT))),
    BelowBlock(((_, blockPos) -> Minecraft.getInstance().level.getBlockState(blockPos.below()).instrument()));

    private final InstrumentDetectFunction instrumentDetectFunction;

    InstrumentDetectMode(InstrumentDetectFunction instrumentDetectFunction) {
        this.instrumentDetectFunction = instrumentDetectFunction;
    }

    public InstrumentDetectFunction getInstrumentDetectFunction() {
        return instrumentDetectFunction;
    }
}
