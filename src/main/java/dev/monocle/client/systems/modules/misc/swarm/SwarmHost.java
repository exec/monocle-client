/* Derived from Meteor Client; Copyright (c) Meteor Development. */
package dev.monocle.client.systems.modules.misc.swarm;

import dev.monocle.client.systems.bots.Bots;
import dev.monocle.coordinator.CrewListener;

import java.net.*;
import java.io.IOException;

public class SwarmHost {
    private CrewListener listener;

    public SwarmHost(int port) {
        try {
            listener = new CrewListener(Bots.get().bindAddress.get(), port, Bots.get()::keyForSelector);
        } catch (IOException e) { Bots.get().error("Cannot start Bots: %s", e.getMessage()); }
    }
    public void disconnect() {
        if (listener != null) listener.close();
    }
    public void sendMessage(String message, String credentialId) {
        if (credentialId == null || credentialId.isEmpty()) return;
        for (var c : getConnections()) if (c != null && c.credentialId().equals(credentialId)) c.send(message);
    }
    public SwarmConnection[] getConnections() { return listener == null ? new SwarmConnection[16] : listener.connections(); }
    public int getConnectionCount() {
        int count = 0;
        for (var c : getConnections()) if (c != null && c.connected()) count++;
        return count;
    }
    public boolean listening() { return listener != null && listener.listening(); }
}
