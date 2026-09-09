/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;

public class Portals extends Module {
    public Portals() {
        super(Categories.Player, "portals", "Allows you to use GUIs normally while in a Nether Portal.");
    }
}
