package dev.monocle.client.systems.bots;

import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import java.io.DataInputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** ./gradlew botsCheck: pure validation/migration and actual compiled registration; no client or files needed. */
public final class BotsTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false; assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        BotJobsTest.run();
        BotWorkflowsTest.run();
        BotLuaTest.run();
        BotRuntimeTest.run();
        BotProfilesTest.run();
        BotActionsTest.main(args);
        var banterLocal = UUID.randomUUID(); var banterOther = UUID.randomUUID();
        assert BotBanter.winner(banterLocal, java.util.Map.of(banterLocal, 3, banterOther, 5)).equals(banterOther);
        assert BotBanter.winner(banterOther, java.util.Map.of(banterLocal, 3, banterOther, 5)) == null;
        assert BotBanter.winner(banterLocal, java.util.Map.of(banterLocal, 5, banterOther, 5)) == null;
        assert BotBanter.winner(banterLocal, java.util.Map.of(banterOther, 1)).equals(banterOther);
        BotStashHuntTest.run();
        BotTaskDataTest.run();
        BotSchedulerTest.run();
        assert Bots.tpaTarget("tpa Worker_1").equals("Worker_1");
        assert Bots.tpaTarget("TPA Worker_1").equals("Worker_1");
        assert Bots.tpaTarget("tpy Worker_1") == null;
        assert Bots.tpaTarget("tpa bad-name") == null;
        sharedCoordinator();
        dev.monocle.client.gui.screens.WorkflowCodeBoxTest.run();

        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        Set<UUID> workers = new HashSet<>(Set.of(first, second));
        var preset = Bots.checkedPreset("  North crew  ", 512, workers);
        assert preset.name().equals("North crew") && preset.length() == 512;
        workers.clear();
        assert preset.workers().equals(Set.of(first, second)) : "Saved membership must not follow live checkbox edits";
        try { preset.workers().clear(); throw new AssertionError("Preset membership must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        for (int length : new int[] {16, 128, 4096, 4097, 100_000}) {
            assert Bots.checkedPreset("x".repeat(48), length, Set.of()).length() == length;
        }
        for (String name : List.of("", "  ", "x".repeat(49), "crew\nname", "crew\u0000name", "crew\u007fname"))
            invalid(() -> Bots.checkedPreset(name, 128, Set.of()));
        for (int length : new int[] {Integer.MIN_VALUE, -1, 0, 15, 100_001, Integer.MAX_VALUE})
            invalid(() -> Bots.checkedPreset("crew", length, Set.of()));
        Set<UUID> fullCrew = new HashSet<>();
        for (int i = 0; i < 3; i++) fullCrew.add(UUID.randomUUID());
        assert Bots.checkedPreset("Three remote workers", 128, fullCrew).workers().size() == 3;
        fullCrew.add(UUID.randomUUID());
        invalid(() -> Bots.checkedPreset("Too many", 128, fullCrew));

        assert Bots.legacySettings(null) == null;
        assert Bots.legacySettings(new CompoundTag()) == null;
        CompoundTag originalSettings = new CompoundTag(); originalSettings.putString("marker", "retained");
        CompoundTag unrelated = new CompoundTag(); unrelated.putString("name", "highway-builder");
        CompoundTag old = new CompoundTag(); old.putString("name", "swarm");
        old.putBoolean("active", true); old.put("settings", originalSettings);
        ListTag modules = new ListTag(); modules.add(unrelated); modules.add(old);
        CompoundTag legacy = new CompoundTag(); legacy.put("modules", modules);
        CompoundTag migrated = Bots.legacySettings(legacy);
        assert migrated != null && migrated.getBooleanOr("active", false) : "A configured worker stays enabled after migration";
        assert migrated.getCompoundOrEmpty("settings").equals(originalSettings);
        migrated.getCompoundOrEmpty("settings").putString("marker", "new");
        assert originalSettings.getStringOr("marker", "").equals("retained") : "Migration must copy, not alter old settings";
        old.remove("active"); old.remove("settings");
        migrated = Bots.legacySettings(legacy);
        assert migrated != null && !migrated.getBooleanOr("active", true);
        assert migrated.getCompoundOrEmpty("settings").isEmpty();
        old.putString("name", "bots");
        assert Bots.legacySettings(legacy) == null : "Only the old Swarm module is a migration source";
        legacy.putString("modules", "invalid");
        assert Bots.legacySettings(legacy) == null;

        int[] delays = {1, 1, 2, 4, 8, 10, 10};
        for (int i = 0; i < delays.length; i++) assert Bots.retrySeconds(i) == delays[i];
        assert Bots.retrySeconds(Integer.MAX_VALUE) == 10;
        credentials();

        ClassModel bots = compiled("systems/bots/Bots");
        assert bots.superclass().orElseThrow().asInternalName().equals("dev/monocle/client/systems/System")
            : "Bots must persist as an independent system, not a module";
        assert calls(method(compiled("systems/Systems"), "init")).contains("dev/monocle/client/systems/bots/Bots.<init>");
        assert calls(compiled("systems/modules/Modules")).stream().noneMatch(call -> call.contains("/Swarm.") || call.contains("/bots/Bots.<init>"))
            : "Neither old Swarm nor the Bots service belongs in module registration";
        assert calls(method(compiled("commands/Commands"), "init")).contains("dev/monocle/client/commands/commands/BotCommand.<init>");
        assert calls(compiled("commands/Commands")).stream().noneMatch(call -> call.contains("/SwarmCommand."));
        var command = method(compiled("commands/commands/BotCommand"), "<init>");
        assert constants(command).contains("worker") && constants(command).contains("bot") && !constants(command).contains("swarm")
            : "Use .worker publicly and retain .bot as a compatibility alias";
        assert calls(method(compiled("gui/tabs/Tabs"), "init")).contains("dev/monocle/client/gui/tabs/builtin/BotsTab.<init>")
            : "The dashboard must be reachable from the top tab bar";
        assert constants(method(compiled("gui/tabs/builtin/BotsTab"), "<init>")).contains("Workers");
        var screenClose = calls(method(compiled("gui/tabs/builtin/BotsTab$BotsScreen"), "onClosed"));
        assert screenClose.contains("dev/monocle/client/systems/bots/Bots.save");
        assert screenClose.stream().noneMatch(call -> call.endsWith(".disable") || call.endsWith(".close") || call.endsWith(".pause"))
            : "Closing the dashboard must not alter worker connections or jobs";
        assert constants(method(compiled("gui/tabs/builtin/BotsTab$BotsScreen"), "refreshConnectionControls"))
            .contains("Disconnect from host")
            : "A connected worker must have an explicit disconnect control";
        assert constants(method(compiled("gui/tabs/builtin/BotsTab$BotsScreen"), "refreshDiscovery")).contains("Join crew job")
            : "Authenticated workers must be able to join a discoverable live crew from the client";
        assert constants(method(bots,"handleManagement")).containsAll(List.of("crew-join-request","assign-crew","crew-join-result"))
            : "Crew discovery admission must reuse authenticated reassignment and acknowledge late joining";
        var inspection = method(compiled("gui/tabs/builtin/BotsTab$InspectionScreen"), "initWidgets");
        assert inspection.code().orElseThrow().elementList().stream().filter(element -> element instanceof FieldInstruction field
            && field.owner().asInternalName().equals("dev/monocle/client/systems/bots/Bots$Mode") && field.name().equalsString("Host")).count() >= 2
            : "Both Resume and End job controls must be host-only";

        var load = method(bots, "fromTag");
        assert calls(load).contains("dev/monocle/client/systems/bots/Bots.hasJobs")
            && calls(load).indexOf("dev/monocle/client/systems/bots/Bots.hasJobs") < calls(load).indexOf("dev/monocle/client/systems/bots/Bots.disable")
            : "Reload must retain live crew controllers before touching connections or maps";
        assert calls(load).contains("dev/monocle/client/systems/bots/Bots.hasPersistedCrewWork")
            : "Profiles must not orphan recovered jobs or pending release acknowledgments by replacing crew keys";
        var rename = calls(method(bots, "renameCrew"));
        assert rename.stream().noneMatch(call -> call.endsWith(".generateKey") || call.endsWith(".reassignWorker") || call.endsWith(".close"))
            : "Renaming is a label change, never a new identity or connection reset";
        assert !calls(method(bots, "syncJobs")).contains("java/lang/Math.max")
            : "A recovered execution checkpoint may rewind; taking the maximum would resurrect stale progress";
        assert calls(method(bots, "syncJobs")).containsAll(List.of("dev/monocle/client/systems/modules/misc/swarm/SwarmCrew.roadComplete",
            "dev/monocle/client/systems/bots/BotJobs.settleReleased", "dev/monocle/client/systems/modules/misc/swarm/SwarmCrew.hasEndedSupplies"))
            : "Catalog completion survives regrouping, but settling still checks abandoned supplies";
        var assignCalls = calls(bots.methods().stream().filter(method -> method.methodName().equalsString("assignJob")
            && method.methodTypeSymbol().parameterCount() == 4).findFirst().orElseThrow());
        assert assignCalls.contains("dev/monocle/client/systems/bots/BotJobs.put")
            && assignCalls.indexOf("dev/monocle/client/systems/bots/BotJobs.put") < assignCalls.indexOf("dev/monocle/client/systems/bots/BotScheduler.queueHighway")
            : "Persist the job claim before queuing the configured worker workflow";
        var nativeStart = calls(method(bots, "startTaskHighway"));
        assert nativeStart.indexOf("dev/monocle/client/systems/bots/BotJobs.put") < nativeStart.indexOf("dev/monocle/client/systems/modules/misc/swarm/SwarmCrew.startJob")
            : "Native task dispatch still commits ownership before sending commands";
        var startHostCalls = calls(method(bots, "startHost"));
        assert startHostCalls.contains("dev/monocle/client/systems/bots/Bots.tickConnection")
            && startHostCalls.indexOf("dev/monocle/client/systems/bots/Bots.tickConnection") < startHostCalls.indexOf("dev/monocle/client/systems/modules/misc/swarm/SwarmHost.<init>")
            : "Apply Worker-to-Host transitions before opening the listener";
        assert calls(load).containsAll(List.of("dev/monocle/client/systems/bots/Bots.disable", "dev/monocle/client/systems/bots/Bots.checkedPreset",
            "dev/monocle/client/systems/bots/Bots.enable", "java/util/UUID.fromString")) : "Restored presets must use live validation";
        assert constants(load).containsAll(List.of("crews", "workers", "name", "length", 32)) : "Bound restored presets and preserve their membership";
        assert constants(method(bots, "toTag")).containsAll(List.of("active", "settings", "crews", "workers", "name", "length"));
        assert calls(method(bots, "load")).contains("dev/monocle/client/systems/bots/Bots.legacySettings");
        assert calls(bots.methods().stream().filter(method -> method.methodName().equalsString("reassignWorker")
            && method.methodTypeSymbol().parameterCount() == 3).findFirst().orElseThrow()).contains("dev/monocle/client/systems/modules/misc/swarm/SwarmConnection.sealSecret");
        var lateJoin = calls(method(bots, "addWorker"));
        assert lateJoin.contains("dev/monocle/client/systems/bots/BotScheduler.joinHighway")
            && lateJoin.indexOf("dev/monocle/client/systems/bots/BotScheduler.joinHighway") < lateJoin.indexOf("dev/monocle/client/systems/modules/misc/swarm/SwarmCrew.addWorker")
            : "Task-managed late admission must prepare the captured runtime/profile before any native lane assignment";
        assert calls(method(bots, "handleManagement")).contains("dev/monocle/client/systems/modules/misc/swarm/SwarmConnection.openSecret");
        assert constants(method(bots, "handleManagement")).contains("sealedKey") && !constants(method(bots, "handleManagement")).contains("key")
            : "Crew reassignment must never accept plaintext credentials";
        assert calls(method(bots, "onTick")).contains("dev/monocle/client/systems/bots/Bots.tickConnection")
            : "Reconnect must run without a module subscription";
        for (String event : List.of("onGameLeft", "onGameJoin")) {
            assert calls(method(bots, event)).stream().noneMatch(call -> call.endsWith(".disable") || call.endsWith(".toggle"))
                : "World transitions must not disable the worker's reconnect loop";
        }
        var send = method(compiled("systems/modules/misc/swarm/SwarmHost"), "sendMessage");
        assert send.methodTypeSymbol().parameterCount() == 2 : "No credential-free host broadcast API";
        assert calls(send).containsAll(List.of("dev/monocle/client/systems/modules/misc/swarm/SwarmConnection.credentialId", "java/lang/String.equals"));
        System.out.println("Bots checks passed: durable independent jobs, malformed catalog preservation, stable crew labels, claims-before-commands, profile recovery guards, immutable presets, legacy migration, authenticated crew isolation and dashboard lifecycle.");
    }

    private static void credentials() throws Exception {
        String a = "test-first-crew-key-at-least-24", b = "test-second-crew-key-at-least-24";
        String selectorA = SwarmConnection.credentialSelector(a), selectorB = SwarmConnection.credentialSelector(b);
        assert selectorA.matches("[0-9a-f]{64}") && !selectorA.equals(selectorB);
        assert selectorA.equals(SwarmConnection.credentialSelector(a));
        invalid(() -> SwarmConnection.credentialSelector("short"));
        var keys = java.util.Map.of(selectorA, a, selectorB, b);
        String previousSession = credentialConnection(a, keys::get, true, null);
        credentialConnection(a, keys::get, true, previousSession); // Same crew key, but a different nonce/session.
        credentialConnection(b, keys::get, true, previousSession);
        credentialConnection("test-unknown-crew-key-at-least-24", keys::get, false, null);
        credentialConnection(a, _ -> b, false, null); // A resolver must not bind a selector to a different key.
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var worker = new SwarmConnection(new Socket(listener.getInetAddress(), listener.getLocalPort()), a, false);
            worker.start();
            try (Socket rawHost = listener.accept()) {
                rawHost.setSoTimeout(5000);
                String hello = new DataInputStream(rawHost.getInputStream()).readUTF();
                assert hello.matches("monocle-crew-6:[0-9a-f-]{36}:[0-9a-f]{64}");
                assert hello.endsWith(":" + selectorA) && !hello.contains(a) : "Never put the private key in the hello";
                assert worker.credentialId().isEmpty() && !worker.connected() : "A selector alone is not authenticated membership";
                unavailable(() -> worker.sealSecret(b));
                unavailable(() -> worker.openSecret(previousSession));
            } finally { worker.disconnect(); worker.join(1000); }
        }
    }

    private static String credentialConnection(String key, Function<String, String> resolver, boolean success, String previousSession) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var worker = new SwarmConnection(new Socket(listener.getInetAddress(), listener.getLocalPort()), key, false);
            var host = new SwarmConnection(listener.accept(), resolver);
            try {
                assert host.credentialId().isEmpty() && worker.credentialId().isEmpty();
                host.start(); worker.start();
                await(() -> host.connected() && worker.connected() || !host.isAlive() || !worker.isAlive());
                if (success) {
                    assert host.connected() && worker.connected();
                    assert host.credentialId().equals(SwarmConnection.credentialSelector(key)) && worker.credentialId().equals(host.credentialId());
                    assert host.send("crew assignment") && worker.send("crew report");
                    String[] received = new String[2];
                    await(() -> (received[0] != null || (received[0] = host.poll()) != null)
                        && (received[1] != null || (received[1] = worker.poll()) != null));
                    assert received[0].equals("crew report") && received[1].equals("crew assignment");
                    String secret = "a-new-private-crew-key-not-sent-in-plaintext";
                    String sealed = host.sealSecret(secret);
                    assert sealed.length() <= 512 && !sealed.contains(secret);
                    assert worker.openSecret(sealed).equals(secret);
                    assert !host.sealSecret(secret).equals(sealed) : "Each transfer needs a fresh GCM nonce";
                    assert host.openSecret(worker.sealSecret(secret)).equals(secret);
                    if (previousSession != null) invalid(() -> worker.openSecret(previousSession));
                    byte[] tampered = java.util.Base64.getDecoder().decode(sealed);
                    tampered[tampered.length - 1] ^= 1;
                    invalid(() -> worker.openSecret(java.util.Base64.getEncoder().encodeToString(tampered)));
                    invalid(() -> worker.openSecret("!invalid base64!"));
                    invalid(() -> worker.openSecret("x".repeat(513)));
                    assert host.sealSecret("x".repeat(356)).length() == 512;
                    invalid(() -> host.sealSecret("x".repeat(357)));
                    return sealed;
                } else {
                    await(() -> !host.isAlive() && !worker.isAlive());
                    assert !host.connected() && !worker.connected();
                    assert host.credentialId().isEmpty() && worker.credentialId().isEmpty();
                    assert !host.failure().isEmpty();
                    assert !host.send("must not dispatch") && host.poll() == null;
                    unavailable(() -> host.sealSecret("not authenticated"));
                    return null;
                }
            } finally { host.disconnect(); worker.disconnect(); host.join(1000); worker.join(1000); }
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for Bots authentication");
            Thread.sleep(5);
        }
    }

    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid crew preset to be rejected"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void unavailable(Runnable action) {
        try { action.run(); throw new AssertionError("Unauthenticated credential transfer must fail"); }
        catch (IllegalStateException expected) { }
    }

    private static void sharedCoordinator() throws Exception {
        var scheduler = compiled("systems/bots/BotScheduler");
        String policy = "dev/monocle/coordinator/QueuePolicy.";
        for (String name : List.of("choose", "checkedPriority", "reconcileMissingRun", "cancellationCommand", "terminal",
            "teleportPending", "sameTeleportServer", "validTeleportTtl", "teleportWarmupReady", "pause", "resume", "cancel")) {
            var invoked = calls(method(scheduler, name));
            assert invoked.contains(policy + name) : "Client host must use the shared core: " + name;
            if (List.of("pause", "resume", "cancel").contains(name))
                assert invoked.indexOf(policy + name) < invoked.indexOf("dev/monocle/client/systems/bots/BotScheduler.save")
                    : "Host persists core decisions before dispatch";
        }
        assert calls(method(scheduler, "schedule")).contains(policy + "preempts");
        assert calls(method(scheduler, "schedule")).contains(policy + "dispatch");
        assert calls(method(scheduler, "transfer")).contains("dev/monocle/coordinator/TaskWire.envelope");
        assert calls(method(scheduler, "updateStatus")).contains("dev/monocle/coordinator/TaskWire.applyStatus");
        assert calls(method(compiled("systems/bots/BotTaskData"), "write")).contains("dev/monocle/coordinator/TaskFiles.write");
        assert calls(method(scheduler, "summarize")).contains(policy + "summarizedStatus");
        String observations = "dev/monocle/coordinator/PlayerObservation.";
        assert calls(method(scheduler, "observeWorker")).contains(observations + "fromHello");
        assert calls(method(scheduler, "observation")).containsAll(List.of(observations + "fresh", "dev/monocle/client/systems/modules/misc/swarm/SwarmConnection.connected"));
        assert calls(method(scheduler, "anchor")).contains(observations + "anchor");
        assert calls(method(scheduler, "prepareTeleport")).contains(observations + "target");
        for (String name : List.of("anchor", "prepareTeleport", "tickTeleports", "createCaptured"))
            assert calls(method(scheduler, name)).stream().noneMatch(c -> c.startsWith("net/minecraft/")) : "World decisions use captured observations: " + name;
        var crew = compiled("systems/modules/misc/swarm/SwarmCrew");
        var sharedCrew=compiled("/dev/monocle/coordinator/HighwayCoordinator");
        assert crew.superclass().orElseThrow().asInternalName().equals("dev/monocle/coordinator/HighwayCoordinator");
        assert calls(method(sharedCrew, "coordinateWindow")).containsAll(List.of("dev/monocle/coordinator/RowVerification.mask", "dev/monocle/coordinator/RowVerification.checkpoint"));
        assert calls(sharedCrew).stream().noneMatch(c -> c.startsWith("net/minecraft/")) : "Native host decisions must run without Minecraft";
        assert calls(method(sharedCrew,"startPrepared")).stream().anyMatch(c -> c.endsWith(".hostIdentity"))
            : "Journal host identity is distinct from an offline-server player UUID";
        assert calls(method(crew,"hostIdentity")).contains("net/minecraft/client/User.getProfileId");
        var drain = calls(method(crew, "drain"));
        assert drain.indexOf("dev/monocle/client/systems/modules/misc/swarm/SwarmCrew.reportedMining") < drain.indexOf("dev/monocle/client/systems/bots/BotScheduler.observeWorker")
            : "Scheduler observations are published only after native validation";
        assert calls(method(compiled("systems/bots/BotRuntime"), "terminal")).contains(policy + "terminal")
            : "Worker and host agree on terminal states";
        assert BotLua.class.getProtectionDomain().getCodeSource().getLocation().equals(dev.monocle.coordinator.QueuePolicy.class.getProtectionDomain().getCodeSource().getLocation())
            : "Lua and queue decisions must come from the same core artifact, not a client-side copy";
    }

    private static ClassModel compiled(String path) throws Exception {
        try (var bytes = BotsTest.class.getResourceAsStream((path.startsWith("/") ? path : "/dev/monocle/client/" + path) + ".class")) {
            if (bytes == null) throw new AssertionError("Missing compiled class: " + path);
            return ClassFile.of().parse(bytes.readAllBytes());
        }
    }
    private static MethodModel method(ClassModel type, String name) {
        return type.methods().stream().filter(method -> method.methodName().equalsString(name)).findFirst().orElseThrow();
    }
    private static List<String> calls(ClassModel type) { return type.methods().stream().flatMap(method -> calls(method).stream()).toList(); }
    private static List<String> calls(MethodModel method) {
        return method.code().stream().flatMap(code -> code.elementList().stream())
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast)
            .map(call -> call.owner().asInternalName() + "." + call.name().stringValue()).toList();
    }
    private static List<Object> constants(MethodModel method) {
        return method.code().orElseThrow().elementList().stream()
            .filter(ConstantInstruction.class::isInstance).map(ConstantInstruction.class::cast)
            .map(constant -> (Object) constant.constantValue()).toList();
    }
}
