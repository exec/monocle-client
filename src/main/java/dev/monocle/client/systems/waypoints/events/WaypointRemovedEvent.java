/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.waypoints.events;

import dev.monocle.client.systems.waypoints.Waypoint;

public record WaypointRemovedEvent(Waypoint waypoint) {
}
