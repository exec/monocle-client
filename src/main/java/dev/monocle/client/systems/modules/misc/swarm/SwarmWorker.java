/* Derived from Meteor Client; Copyright (c) Meteor Development. */
package dev.monocle.client.systems.modules.misc.swarm;

import dev.monocle.client.systems.bots.Bots;
import dev.monocle.coordinator.CrewTransport;

import dev.monocle.client.pathing.PathManagers;
import net.minecraft.world.level.block.Block;

public class SwarmWorker extends SwarmConnection {
    public Block target;
    public SwarmWorker(String ip, int port) {
        this(ip, port, Bots.get().crewKey.get());
    }
    public SwarmWorker(String ip, int port, String key) {
        super(CrewTransport.worker(ip, port), key, false);
        start();
    }
    public void tick() {
        if (target != null) {
            PathManagers.get().stop();
            PathManagers.get().mine(target);
            target = null;
        }
    }
}
