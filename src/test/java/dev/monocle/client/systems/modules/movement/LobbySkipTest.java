package dev.monocle.client.systems.modules.movement;

import dev.monocle.client.commands.commands.BotCommand;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import dev.monocle.client.utils.network.WorldReadiness;
import java.util.List;

/** ./gradlew lobbySkipCheck; bounded manual retry and background-input wiring. */
public final class LobbySkipTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        assert LobbySkip.supportedServer("play.6b6t.org:25565");
        assert LobbySkip.supportedServer("ALT.6B6T.ORG");
        assert LobbySkip.supportedServer("6b6t.org");
        assert !LobbySkip.supportedServer("fake6b6t.org");
        assert !LobbySkip.supportedServer("6b6t.org.evil.example");
        assert !LobbySkip.supportedServer("localhost:25565");
        assert LobbySkip.withinWalkBudget(0);
        assert LobbySkip.withinWalkBudget(1199);
        assert !LobbySkip.withinWalkBudget(1200);
        var lobby = new Object();
        var game = new Object();
        var tracker = new WorldReadiness.Tracker();
        tracker.connect(true);
        assert tracker.state().equals("Connecting") && tracker.isInLobby(lobby);
        tracker.join(lobby); tracker.tick(lobby, true);
        assert tracker.isInLobby(lobby) && tracker.state().equals("Lobby");
        assert !tracker.isInLobby(game) && !tracker.isInLobby(null);
        assert !calls(LobbySkip.class, "isInLobby").contains("isActive");
        for (String method : List.of("onDeactivate", "finishLobby", "stopSkipping"))
            assert !calls(LobbySkip.class, method).contains("markLobby") : "Movement cleanup cannot reset passive detection";
        assert !tracker.canRecognizeTeleport();
        for (int i=0;i<20;i++) tracker.tick(lobby,true);
        assert tracker.canRecognizeTeleport() : "Teleport detection advances without walking or enabling a module";
        tracker.transfer(); tracker.tick(lobby,true);
        assert tracker.state().equals("Transferring") && tracker.isInLobby(lobby) : "Receipt is not an applied lobby exit";
        tracker.leave(); tracker.join(game); tracker.tick(game,false);
        assert !tracker.isInLobby(game) && tracker.state().equals("Loading");
        tracker.tick(game,true); assert tracker.state().equals("Ready");
        tracker.leave(); assert tracker.state().equals("Disconnected");
        tracker.connect(false); tracker.join(game); tracker.tick(game,true);
        assert !tracker.isInLobby(game) && tracker.state().equals("Ready");
        tracker.markLobby(lobby); tracker.tick(game,true);
        assert !tracker.isInLobby(game) : "Replacement world retires the old lobby";
        tracker.markLobby(lobby); tracker.appliedTransition(lobby); tracker.tick(lobby,true);
        assert !tracker.isInLobby(lobby) : "A server-applied same-world teleport can exit the lobby";
        var inventory = new WorldReadiness.InventoryTracker();var player = new Object();var replacement = new Object();
        assert !inventory.ready(player) && !inventory.ready(null);
        inventory.observe(player, 1, 0, 46, 46);
        assert !inventory.ready(player) : "A container menu cannot establish player-inventory readiness";
        inventory.observe(player, 0, 0, 45, 46);
        assert !inventory.ready(player) : "An incomplete player snapshot cannot establish readiness";
        inventory.observe(player, 0, 0, 46, 46);
        assert inventory.ready(player) && !inventory.ready(replacement) : "Readiness belongs to the exact player instance";
        inventory.clear();assert !inventory.ready(player) : "Reconnect/respawn retire old inventory evidence";
        inventory.observe(replacement, 0, 0, 46, 46);
        assert inventory.ready(replacement) && !inventory.ready(player);
        for (String method : List.of("onConnect", "onJoin", "onLeft", "onServerRespawn"))
            assert calls(WorldReadiness.class, method).contains("clear") : "Every applied inventory lifecycle transition resets synchronization";
        assert calls(WorldReadiness.class, "onInventory").containsAll(List.of("isSameThread", "observe"))
            : "Only applied, client-thread inventory packets authorize maintenance";
        assert !calls(LobbySkip.class, "onPacketReceive").contains("leaveLobby") : "Receiving a transfer is not an applied world change";
        for (String method : List.of("onServerRespawn", "onServerTeleport"))
            assert calls(LobbySkip.class, method).containsAll(List.of("isActive", "isSameThread", "leaveLobby"))
                : "Applied transitions must re-arm protection only on the client thread";
        assert calls(dev.monocle.client.mixin.ClientPacketListenerMixin.class, "onHandleMovePlayerHead").contains("onServerTeleport");
        assert calls(dev.monocle.client.mixin.ClientPacketListenerMixin.class, "monocle$lobbyRespawn").contains("onServerRespawn");
        assert calls(LobbySkip.class, "startSkipping").containsAll(List.of("supportedServer", "toggle", "releaseInput"));
        assert calls(LobbySkip.class, "onTick").containsAll(List.of("withinWalkBudget", "forward", "finishLobby"));
        assert !calls(LobbySkip.class, "onTick").contains("setDown") : "No dependence on focused key state";
        assert calls(LobbySkip.class, "finishLobby").contains("releaseInput");
        assert calls(LobbySkip.class, "onGameLeft").contains("releaseInput");
        assert calls(LobbySkip.class, "onDeactivate").contains("releaseInput");
        try (var bytes = BotCommand.class.getResourceAsStream("BotCommand.class")) {
            var compiled = ClassFile.of().parse(bytes.readAllBytes());
            var calls = compiled.methods().stream().flatMap(m -> m.code().stream()).flatMap(c -> c.elementList().stream())
                .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert calls.containsAll(List.of("startSkipping", "stopSkipping", "status")) : "Worker commands expose start/stop/status";
        }
        System.out.println("Lobby Skip checks passed: server boundaries, finite walk budget, lobby-only protection, command dispatch and background-safe input cleanup.");
    }
    private static List<String> calls(Class<?> type, String method) throws Exception {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return ClassFile.of().parse(bytes.readAllBytes()).methods().stream().filter(m -> m.methodName().equalsString(method))
                .findFirst().orElseThrow().code().orElseThrow().elementList().stream().filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
        }
    }
}
