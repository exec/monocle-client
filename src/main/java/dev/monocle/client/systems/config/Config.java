/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.config;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.renderer.Fonts;
import dev.monocle.client.renderer.text.FontFace;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.System;
import dev.monocle.client.systems.Systems;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.render.color.SettingColor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

import static dev.monocle.client.MonocleClient.mc;

public class Config extends System<Config> {
    public final Settings settings = new Settings();

    private final SettingGroup sgVisual = settings.createGroup("Visual");
    private final SettingGroup sgModules = settings.createGroup("Modules");
    private final SettingGroup sgChat = settings.createGroup("Chat");
    private final SettingGroup sgMisc = settings.createGroup("Misc");
    private final SettingGroup sgNotifications = settings.createGroup("Notification Feed");
    private final SettingGroup sgBots = settings.createGroup("Bots");

    public final Setting<Boolean> banterMode = sgBots.add(new BoolSetting.Builder()
        .name("banter-mode")
        .description("When rare edge cases are caused by other workers, a worker makes it known to the general public that the other is a bitch. Sends public chat; limited to one message per confirmed incident.")
        .defaultValue(false).build());

    public final Setting<Integer> botJobHistoryDays = sgBots.add(new IntSetting.Builder()
        .name("job-history-retention-days").description("Delete finished Bots job history after this many days. Zero disables history retention. Active jobs and recovery records are never expired.")
        .defaultValue(30).range(0, 3650).sliderMax(90).build());

    public final Setting<Boolean> notificationFeed = sgNotifications.add(new BoolSetting.Builder()
        .name("enabled").description("Show notifications explicitly routed to the shared feed.").defaultValue(true).build());
    public final Setting<dev.monocle.client.utils.render.Notifications.Output> moduleNotificationOutput = sgNotifications.add(new EnumSetting.Builder<dev.monocle.client.utils.render.Notifications.Output>()
        .name("overhauled-module-output").description("Destination for all built-in modules' feedback and toggles. Legacy setting name retained. IRC, commands, outgoing server messages and actionable chat confirmations are unchanged; Notifier categories keep their own settings.")
        .defaultValue(dev.monocle.client.utils.render.Notifications.Output.Feed).build());
    public final Setting<Integer> notificationWidth = sgNotifications.add(new IntSetting.Builder()
        .name("width").description("Card width in GUI-scaled pixels.").defaultValue(240).range(100, 500).build());
    public final Setting<Integer> notificationHeight = sgNotifications.add(new IntSetting.Builder()
        .name("maximum-height-percent").description("Maximum screen height; always keeps a bottom margin.").defaultValue(40).range(10, 100).build());
    public final Setting<Integer> notificationRight = sgNotifications.add(new IntSetting.Builder()
        .name("right-offset").description("Distance from the right edge in GUI-scaled pixels.").defaultValue(12).range(8, 1000).build());
    public final Setting<Integer> notificationTop = sgNotifications.add(new IntSetting.Builder()
        .name("top-offset").description("Distance from the top edge in GUI-scaled pixels.").defaultValue(12).range(8, 1000).build());
    public final Setting<Double> notificationDuration = sgNotifications.add(new DoubleSetting.Builder()
        .name("duration").description("Seconds before a card expires; grouped updates renew its lifetime.").defaultValue(6).range(1, 30).build());
    public final Setting<Integer> notificationLines = sgNotifications.add(new IntSetting.Builder()
        .name("maximum-lines").description("Maximum body lines per card.").defaultValue(3).range(1, 6).build());
    public final Setting<Boolean> notificationGrouping = sgNotifications.add(new BoolSetting.Builder()
        .name("group-updates").description("Update matching source/key cards without reordering them.").defaultValue(true).build());
    public final Setting<Boolean> notificationSound = sgNotifications.add(new BoolSetting.Builder()
        .name("sound").description("Play a sound for new visible cards, at most once per second.").defaultValue(false).build());

    // Visual

    public final Setting<Boolean> customFont = sgVisual.add(new BoolSetting.Builder()
        .name("custom-font")
        .description("Use a custom font.")
        .defaultValue(true)
        .build()
    );

    public final Setting<FontFace> font = sgVisual.add(new FontFaceSetting.Builder()
        .name("font")
        .description("Custom font to use.")
        .visible(customFont::get)
        .onChanged(Fonts::load)
        .build()
    );

    public final Setting<Double> rainbowSpeed = sgVisual.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .description("The global rainbow speed.")
        .defaultValue(0.5)
        .range(0, 10)
        .sliderMax(5)
        .build()
    );

    public final Setting<Boolean> titleScreenCredits = sgVisual.add(new BoolSetting.Builder()
        .name("title-screen-credits")
        .description("Show Monocle credits on title screen")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> titleScreenSplashes = sgVisual.add(new BoolSetting.Builder()
        .name("title-screen-splashes")
        .description("Show Monocle splash texts on title screen")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> customWindowTitle = sgVisual.add(new BoolSetting.Builder()
        .name("custom-window-title")
        .description("Show custom text in the window title.")
        .defaultValue(false)
        .onModuleActivated(_ -> mc.updateTitle())
        .onChanged(_ -> mc.updateTitle())
        .build()
    );

    public final Setting<String> customWindowTitleText = sgVisual.add(new StringSetting.Builder()
        .name("window-title-text")
        .description("The text it displays in the window title.")
        .visible(customWindowTitle::get)
        .defaultValue("Minecraft {mc_version} - {monocle.name} {monocle.version}")
        .onChanged(_ -> mc.updateTitle())
        .build()
    );

    public final Setting<SettingColor> friendColor = sgVisual.add(new ColorSetting.Builder()
        .name("friend-color")
        .description("The color used to show friends.")
        .defaultValue(new SettingColor(0, 255, 180))
        .build()
    );

    public final Setting<Boolean> syncListSettingWidths = sgVisual.add(new BoolSetting.Builder()
        .name("sync-list-setting-widths")
        .description("Prevents the list setting screens from moving around as you add & remove elements.")
        .defaultValue(false)
        .build()
    );

    public final Setting<ButtonPosition> accountButtonAnchor = sgVisual.add(new EnumSetting.Builder<ButtonPosition>()
        .name("accounts-button")
        .description("Controls the position and visibility of the accounts button in the multiplayer screen.")
        .defaultValue(ButtonPosition.TopRight)
        .build()
    );

    public final Setting<Boolean> showAccountStatus = sgVisual.add(new BoolSetting.Builder()
        .name("account-status")
        .description("Shows information about the current account in the multiplayer screen.")
        .defaultValue(true)
        .build()
    );

    public final Setting<ButtonPosition> proxiesButtonAnchor = sgVisual.add(new EnumSetting.Builder<ButtonPosition>()
        .name("proxies-button")
        .description("Controls the position and visibility of the proxies button in the multiplayer screen.")
        .defaultValue(ButtonPosition.TopRight)
        .build()
    );

    public final Setting<Boolean> showProxiesStatus = sgVisual.add(new BoolSetting.Builder()
        .name("proxy-status")
        .description("Shows information about the current proxy in the multiplayer screen.")
        .defaultValue(true)
        .build()
    );

    // Modules

    public final Setting<List<Module>> hiddenModules = sgModules.add(new ModuleListSetting.Builder()
        .name("hidden-modules")
        .description("Prevent these modules from being rendered as options in the clickgui.")
        .build()
    );

    public final Setting<Integer> moduleSearchCount = sgModules.add(new IntSetting.Builder()
        .name("module-search-count")
        .description("Amount of modules and settings to be shown in the module search bar.")
        .defaultValue(8)
        .min(1).sliderMax(12)
        .build()
    );

    public final Setting<Boolean> moduleAliases = sgModules.add(new BoolSetting.Builder()
        .name("search-module-aliases")
        .description("Whether or not module aliases will be used in the module search bar.")
        .defaultValue(true)
        .build()
    );

    // Chat

    public final Setting<String> prefix = sgChat.add(new StringSetting.Builder()
        .name("prefix")
        .description("Prefix.")
        .defaultValue(".")
        .build()
    );

    public final Setting<Boolean> chatFeedback = sgChat.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Sends chat feedback when monocle performs certain actions.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> deleteChatFeedback = sgChat.add(new BoolSetting.Builder()
        .name("delete-chat-feedback")
        .description("Delete previous matching chat feedback to keep chat clear.")
        .visible(chatFeedback::get)
        .defaultValue(true)
        .build()
    );

    // Misc

    public final Setting<Integer> rotationHoldTicks = sgMisc.add(new IntSetting.Builder()
        .name("rotation-hold")
        .description("Hold long to hold server side rotation when not sending any packets.")
        .defaultValue(4)
        .build()
    );

    public final Setting<Boolean> useTeamColor = sgMisc.add(new BoolSetting.Builder()
        .name("use-team-color")
        .description("Uses player's team color for rendering things like esp and tracers.")
        .defaultValue(true)
        .build()
    );

    public List<String> dontShowAgainPrompts = new ArrayList<>();

    public Config() {
        super("config");
    }

    public static Config get() {
        return Systems.get(Config.class);
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();

        tag.putString("version", MonocleClient.VERSION.toString());
        tag.put("settings", settings.toTag());
        tag.put("dontShowAgainPrompts", listToTag(dontShowAgainPrompts));

        return tag;
    }

    @Override
    public Config fromTag(CompoundTag tag) {
        if (tag.contains("settings")) settings.fromTag(tag.getCompoundOrEmpty("settings"));
        if (tag.contains("dontShowAgainPrompts")) dontShowAgainPrompts = listFromTag(tag, "dontShowAgainPrompts");

        return this;
    }

    private ListTag listToTag(List<String> list) {
        ListTag nbt = new ListTag();
        for (String item : list) nbt.add(StringTag.valueOf(item));
        return nbt;
    }

    private List<String> listFromTag(CompoundTag tag, String key) {
        List<String> list = new ArrayList<>();
        for (Tag item : tag.getListOrEmpty(key)) list.add(item.asString().orElse(""));
        return list;
    }

    public enum ButtonPosition {
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
        Hidden,
    }
}
