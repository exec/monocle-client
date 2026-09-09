/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.misc;

import dev.monocle.client.events.entity.EntityAddedEvent;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.settings.StringSetting;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.player.Player;

public class MessageAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> message = sgGeneral.add(new StringSetting.Builder()
        .name("message")
        .description("The specified message sent to the player.")
        .defaultValue("Monocle: A Clear Advantage.")
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Will not send any messages to people friended.")
        .defaultValue(false)
        .build()
    );

    public MessageAura() {
        super(Categories.Misc, "message-aura", "Sends a specified message to any player that enters render distance.");
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof Player) || event.entity.getUUID().equals(mc.player.getUUID())) return;

        if (!ignoreFriends.get() || (ignoreFriends.get() && !Friends.get().isFriend((Player) event.entity))) {
            ChatUtils.sendPlayerMsg("/msg " + event.entity.getName().getString() + " " + message.get());
        }
    }
}
