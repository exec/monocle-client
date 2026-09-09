/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.misc;

import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;

public class BetterBeacons extends Module {
    public BetterBeacons() {
        super(Categories.Misc, "better-beacons", "Select effects unaffected by beacon level.");
    }
}
