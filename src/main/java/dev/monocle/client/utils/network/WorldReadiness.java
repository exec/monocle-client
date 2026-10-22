package dev.monocle.client.utils.network;

import com.google.gson.JsonObject;
import dev.monocle.client.events.game.GameJoinedEvent;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.world.ServerConnectBeginEvent;
import dev.monocle.client.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.entity.Relative;
import static dev.monocle.client.MonocleClient.mc;

/** Passive connection knowledge; movement modules cannot clear it by toggling. */
public final class WorldReadiness {
    public static final WorldReadiness INSTANCE = new WorldReadiness();
    private final Tracker tracker = new Tracker();
    private final InventoryTracker inventory = new InventoryTracker();
    private WorldReadiness() {}

    public static final class InventoryTracker {
        private Object player;
        public boolean ready(Object current) { return current != null && current == player; }
        public void observe(Object current, int receivedMenu, int inventoryMenu, int receivedSlots, int inventorySlots) {
            if (current != null && receivedMenu == inventoryMenu && receivedSlots == inventorySlots) player = current;
        }
        public void clear() { player = null; }
    }

    public static final class Tracker {
        private Object world;
        private boolean lobby, transferring, connected;
        private int joinedTicks;
        private String state = "Disconnected";
        public void connect(boolean lobbyServer) {
            world = null; lobby = lobbyServer; transferring = false; connected = true; joinedTicks = 0; state = "Connecting";
        }
        public void join(Object next) {
            if (world != null && world != next || transferring) appliedTransition(next);
            world = next; connected = true; joinedTicks = 0;
        }
        public void transfer() { transferring = true; state = "Transferring"; }
        public void leave() {
            world = null; joinedTicks = 0;
            if (transferring) return;
            lobby = connected = false; state = "Disconnected";
        }
        public void markLobby(Object next) { world = next; lobby = connected = true; transferring = false; joinedTicks = 0; state="Lobby"; }
        public void appliedTransition(Object next) { world = next; lobby = transferring = false; state = "Loading"; }
        public void tick(Object next, boolean chunksReady) {
            if (next != null && world != null && next != world) appliedTransition(next);
            if (next != null) { world = next; joinedTicks++; }
            state = transferring ? "Transferring" : next == null ? connected ? "Connecting" : "Disconnected"
                : lobby ? "Lobby" : chunksReady ? "Ready" : "Loading";
        }
        public boolean isInLobby(Object current) { return lobby && current != null && (world == null || current == world); }
        public boolean canRecognizeTeleport() { return lobby && joinedTicks >= 20; }
        public String state() { return state; }
    }

    public static boolean lobbyServer(String address) {
        String host = address.toLowerCase(java.util.Locale.ROOT).split(":", 2)[0];
        return host.equals("6b6t.org") || host.endsWith(".6b6t.org");
    }
    private void refresh() {
        tracker.tick(mc.level, mc.player != null && mc.level != null
            && mc.level.getChunkSource().hasChunk(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4));
    }
    public boolean isInLobby() { return tracker.isInLobby(mc.level); }
    public boolean ready() { return tracker.state().equals("Ready"); }
    public boolean inventoryReady() { return inventory.ready(mc.player); }
    public void markLobby() { tracker.markLobby(mc.level); }
    public JsonObject snapshot() {
        JsonObject report = new JsonObject(); report.addProperty("state", tracker.state());
        report.addProperty("inventoryReady", inventoryReady());
        report.addProperty("reason", switch (tracker.state()) {
            case "Ready" -> "Player and local chunk loaded";
            case "Lobby" -> "Waiting for an applied lobby exit";
            case "Transferring" -> "Server world transfer in progress";
            case "Loading" -> "Waiting for player and local chunk";
            case "Connecting" -> "Waiting for world join";
            default -> "No Minecraft connection";
        });
        return report;
    }
    @EventHandler(priority = EventPriority.HIGHEST + 2)
    private void onConnect(ServerConnectBeginEvent event) { inventory.clear(); tracker.connect(lobbyServer(event.info.ip)); }
    @EventHandler(priority = EventPriority.HIGHEST + 2)
    private void onJoin(GameJoinedEvent event) { inventory.clear(); tracker.join(mc.level); refresh(); }
    @EventHandler(priority = EventPriority.HIGHEST + 2)
    private void onLeft(GameLeftEvent event) { inventory.clear(); tracker.leave(); }
    @EventHandler(priority = EventPriority.HIGHEST + 2)
    private void onTick(TickEvent.Pre event) { refresh(); }
    @EventHandler(priority = EventPriority.HIGHEST + 2)
    private void onInventory(InventoryEvent event) {
        // InventoryEvent is posted after vanilla applies the full packet, not on the network thread.
        if (!mc.isSameThread() || mc.player == null) return;
        inventory.observe(mc.player, event.packet.containerId(), mc.player.inventoryMenu.containerId,
            event.packet.items().size(), mc.player.inventoryMenu.slots.size());
    }
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundTransferPacket || event.packet instanceof ClientboundStartConfigurationPacket) {
            if (mc.isSameThread()) tracker.transfer(); else mc.execute(tracker::transfer);
        }
    }
    public void onServerRespawn() { if (mc.isSameThread()) { inventory.clear(); tracker.appliedTransition(mc.level); } }
    public void onServerTeleport(ClientboundPlayerPositionPacket packet) {
        if (mc.isSameThread() && mc.player != null && tracker.canRecognizeTeleport()
            && !packet.relatives().contains(Relative.X) && !packet.relatives().contains(Relative.Z)
            && packet.change().position().distanceToSqr(mc.player.position()) > 256) tracker.appliedTransition(mc.level);
    }
}
