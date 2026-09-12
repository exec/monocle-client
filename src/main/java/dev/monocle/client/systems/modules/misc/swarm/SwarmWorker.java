/* Derived from Meteor Client; Copyright (c) Meteor Development. */
package dev.monocle.client.systems.modules.misc.swarm;

import dev.monocle.client.systems.bots.Bots;

import dev.monocle.client.pathing.PathManagers;
import net.minecraft.world.level.block.Block;
import java.net.*;
import java.io.IOException;

public class SwarmWorker extends SwarmConnection {
    public Block target;
    private final String ip;
    private final int port;
    public SwarmWorker(String ip, int port) {
        this(ip, port, Bots.get().crewKey.get());
    }
    public SwarmWorker(String ip, int port, String key) {
        super(new Socket(), key, false);
        this.ip = ip;
        this.port = port;
        start();
    }
    @Override protected void connectSocket() throws IOException {
        // localhost explicitly uses IPv4 to match the default 127.0.0.1 host bind, not an OS-dependent ::1.
        InetAddress address = InetAddress.getByName(ip.equalsIgnoreCase("localhost") ? "127.0.0.1" : ip);
        if (!address.isLoopbackAddress() && !address.isSiteLocalAddress()) throw new IOException("Use a LAN or loopback Bots host");
        socket.connect(new InetSocketAddress(address, port), 3000);
    }
    public void tick() {
        if (target != null) {
            PathManagers.get().stop();
            PathManagers.get().mine(target);
            target = null;
        }
    }
}
