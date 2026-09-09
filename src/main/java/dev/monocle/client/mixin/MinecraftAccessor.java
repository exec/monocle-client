/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.ResourceLoadStateTracker;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.server.Services;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("fps")
    static int monocle$getFps() {
        return 0;
    }

    @Mutable
    @Accessor("user")
    void monocle$setUser(User session);

    @Accessor("reloadStateTracker")
    ResourceLoadStateTracker monocle$getReloadStateTracker();

    @Accessor("missTime")
    int monocle$getMissTime();

    @Accessor("missTime")
    void monocle$setMissTime(int attackCooldown);

    @Invoker("startAttack")
    boolean monocle$leftClick();

    @Mutable
    @Accessor("profileKeyPairManager")
    void monocle$setProfileKeyPairManager(ProfileKeyPairManager keys);

    @Mutable
    @Accessor("userApiService")
    void monocle$setUserApiService(UserApiService apiService);

    @Mutable
    @Accessor("skinManager")
    void monocle$setSkinManager(SkinManager skinProvider);

    @Mutable
    @Accessor("playerSocialManager")
    void monocle$setPlayerSocialManager(PlayerSocialManager socialInteractionsManager);

    @Mutable
    @Accessor("reportingContext")
    void monocle$setReportingContext(ReportingContext abuseReportContext);

    @Mutable
    @Accessor("profileFuture")
    void monocle$setProfileFuture(CompletableFuture<ProfileResult> future);

    @Mutable
    @Accessor("services")
    void monocle$setServices(Services apiServices);

    @Invoker("handleKeybinds")
    void monocle$handleInputEvents();
}
