/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.mojang.authlib.GameProfile;
import dev.monocle.client.mixininterface.IGuiMessageVisible;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(GuiMessage.Line.class)
public abstract class GuiMessageVisibleMixin implements IGuiMessageVisible {
    @Shadow
    @Final
    private FormattedCharSequence content;
    @Unique
    private int id;
    @Unique
    private GameProfile sender;
    @Unique
    private boolean startOfEntry;

    @Override
    public String monocle$getText() {
        StringBuilder sb = new StringBuilder();

        content.accept((_, _, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });

        return sb.toString();
    }

    @Override
    public int monocle$getId() {
        return id;
    }

    @Override
    public void monocle$setId(int id) {
        this.id = id;
    }

    @Override
    public GameProfile monocle$getSender() {
        return sender;
    }

    @Override
    public void monocle$setSender(GameProfile profile) {
        sender = profile;
    }

    @Override
    public boolean monocle$isStartOfEntry() {
        return startOfEntry;
    }

    @Override
    public void monocle$setStartOfEntry(boolean start) {
        startOfEntry = start;
    }
}
