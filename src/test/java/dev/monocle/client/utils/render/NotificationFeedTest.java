package dev.monocle.client.utils.render;

import java.util.List;

public final class NotificationFeedTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Assertions required");
        var feed = new NotificationFeed();
        var info = NotificationFeed.Severity.Info;
        feed.post("A", "player", "first", info, 0, 100, true);
        feed.post("B", "player", "second", info, 1, 100, true);
        long firstId = feed.snapshot(1, 100).getFirst().id();
        feed.post("A", "player", "updated", NotificationFeed.Severity.Warning, 5, 100, true);
        var snapshot = feed.snapshot(5, 100);
        assert snapshot.size() == 2;
        assert snapshot.getFirst().id() == firstId && snapshot.getFirst().text().equals("updated");
        assert snapshot.getLast().source().equals("B") : "Grouping must not reorder or cross sources";
        assert feed.snapshot(102, 100).size() == 1 : "Grouping refreshes expiration";
        feed.dismiss(firstId);
        assert feed.snapshot(103, 100).isEmpty() : "Scrolled-out cards never return";
        assert feed.history().size() == 3;
        for (int i = 0; i < 10000; i++) feed.post("A", "", "burst " + i, info, 104, 100, false);
        assert feed.snapshot(104, 100).size() == 64;
        assert feed.history().size() == 100;
        assert feed.snapshot(204, 100).isEmpty();
        feed.clear();
        assert feed.history().isEmpty();
        assert NotificationFeed.plain("a\u0000b\u202ec§d", 20).equals("abc");
        assert NotificationFeed.plain("§7[§a+] §fSteve", 50).equals("[+] Steve");
        assert NotificationFeed.plain("😀".repeat(1000), 512).codePointCount(0, 1024) == 512;
        for (int w : List.of(0, 8, 100, 320, 1920)) for (int h : List.of(0, 8, 100, 240, 1080)) {
            for (int offset : List.of(8, 12, 1000)) for (int percent : List.of(10, 40, 100)) {
                var b = NotificationFeed.bounds(w, h, 240, offset, offset, percent);
                assert b.x() >= 0 && b.y() >= 0 && b.width() >= 0 && b.height() >= 0;
                assert b.x() + b.width() <= w && b.y() + b.height() <= h : b;
                if (b.height() > 0) assert b.y() + b.height() <= h - 8;
            }
        }
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 4; i++) threads.add(Thread.startVirtualThread(() -> {
            for (int j = 0; j < 1000; j++) feed.post("thread", "", "message", info, 500, 100, false);
        }));
        for (var thread : threads) thread.join();
        assert feed.snapshot(500, 100).size() == 64 && feed.history().size() == 100;
        assert dev.monocle.client.systems.modules.misc.Notifier.ignoredPop(false, true, false, true, false);
        assert !dev.monocle.client.systems.modules.misc.Notifier.ignoredPop(false, true, false, false, true);
        assert dev.monocle.client.systems.modules.misc.Notifier.ignoredPop(false, false, false, false, true);
        assert !dev.monocle.client.systems.modules.misc.Notifier.ignoredPop(true, false, false, true, true);
        try (var bytes = NotificationFeedTest.class.getResourceAsStream("/dev/monocle/client/systems/modules/misc/Notifier.class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var receive = model.methods().stream().filter(m -> m.methodName().equalsString("onReceivePacket")).findFirst().orElseThrow();
            var calls = receive.code().orElseThrow().elementList().stream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
            assert calls.contains("offer") && !calls.contains("send") && !calls.contains("notify") : "Packet callbacks enqueue bounded work only";
        }
        for (String name : List.of("highway-builder", "printer-helper", "schematic-selector", "stash-finder",
            "inventory-manager", "auto-log", "auto-eat", "auto-gap", "auto-mend", "auto-armor", "auto-tool",
            "kill-aura", "surround", "scaffold", "elytra-fly", "chest-swap", "auto-totem", "auto-replenish", "auto-reconnect", "notifier")) {
            assert Notifications.migratedModule(name) : name;
        }
        for (String name : List.of("community-chat", "", " ")) assert !Notifications.migratedModule(name);
        assert !Notifications.migratedModule(null);
        for (String name : List.of("spam", "crystal-aura", "packet-logger", "notebot", "future-built-in")) assert Notifications.migratedModule(name);
        var batchTwo = java.util.Map.ofEntries(
            java.util.Map.entry("auto-anvil", "combat/AutoAnvil"), java.util.Map.entry("auto-city", "combat/AutoCity"),
            java.util.Map.entry("burrow", "combat/Burrow"), java.util.Map.entry("quiver", "combat/Quiver"),
            java.util.Map.entry("bed-aura", "combat/BedAura"), java.util.Map.entry("offhand", "combat/Offhand"),
            java.util.Map.entry("anchor-aura", "combat/AnchorAura"), java.util.Map.entry("excavator", "world/Excavator"),
            java.util.Map.entry("echest-farmer", "world/EChestFarmer"), java.util.Map.entry("auto-smelter", "world/AutoSmelter"),
            java.util.Map.entry("auto-brewer", "world/AutoBrewer"), java.util.Map.entry("spawn-proofer", "world/SpawnProofer"),
            java.util.Map.entry("auto-nametag", "world/AutoNametag"), java.util.Map.entry("infinity-miner", "world/InfinityMiner"),
            java.util.Map.entry("nuker", "world/Nuker"), java.util.Map.entry("auto-walk", "movement/AutoWalk"),
            java.util.Map.entry("long-jump", "movement/LongJump"), java.util.Map.entry("blink", "movement/Blink"),
            java.util.Map.entry("auto-wasp", "movement/AutoWasp"), java.util.Map.entry("anti-afk", "player/AntiAFK"));
        assert batchTwo.size() == 20;
        for (var entry : batchTwo.entrySet()) {
            assert Notifications.migratedModule(entry.getKey());
            try (var bytes = NotificationFeedTest.class.getResourceAsStream("/dev/monocle/client/systems/modules/" + entry.getValue() + ".class")) {
                var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
                var calls = model.methods().stream().flatMap(m -> m.code().stream())
                    .flatMap(code -> code.elementList().stream())
                    .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
                assert calls.stream().anyMatch(List.of("info", "warning", "error")::contains) : "Must have real feedback: " + entry.getKey();
                assert !calls.contains("sendMsg") && !calls.contains("sendSystemMessage") : "Direct local-chat bypass: " + entry.getKey();
                assert model.methods().stream().noneMatch(m -> List.of("info", "warning", "error", "sendToggledMsg").contains(m.methodName().stringValue()));
                if (entry.getKey().equals("anti-afk")) assert calls.contains("sendPlayerMsg") : "Preserve intentional server messages";
            }
        }
        assert dev.monocle.client.utils.player.ChatUtils.formatMsg("Road: (highlight)100(default) blocks.", net.minecraft.ChatFormatting.GRAY)
            .getString().equals("Road: 100 blocks.");
        try (var bytes = NotificationFeedTest.class.getResourceAsStream("/dev/monocle/client/systems/modules/Module.class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            for (var method : model.methods()) {
                if (!List.of("info", "warning", "error", "sendToggledMsg").contains(method.methodName().stringValue())) continue;
                var calls = method.code().orElseThrow().elementList().stream()
                    .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
                assert calls.contains("feed") || calls.contains("feedFormatted") : method.methodName();
                int route = Math.max(calls.indexOf("feed"), calls.indexOf("feedFormatted"));
                assert route < calls.indexOf("forceNextPrefixClass") : "Feed-only must not pollute the next chat prefix";
            }
        }
        try (var bytes = NotificationFeedTest.class.getResourceAsStream("/dev/monocle/client/systems/modules/world/StashFinder.class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var method = model.methods().stream().filter(m -> m.methodName().equalsString("sendChatNotification")).findFirst().orElseThrow();
            var calls = method.code().orElseThrow().elementList().stream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
            assert calls.contains("info") && !calls.contains("sendMsg");
        }
        var root = java.nio.file.Path.of(dev.monocle.client.systems.modules.Module.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("dev/monocle/client/systems/modules");
        int audited = 0;
        try (var files = java.nio.file.Files.walk(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                audited++;
                var model = java.lang.classfile.ClassFile.of().parse(java.nio.file.Files.readAllBytes(file));
                var directChat = model.methods().stream().flatMap(m -> m.code().stream())
                    .flatMap(code -> code.elementList().stream())
                    .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                    .filter(i -> i.owner().asInternalName().equals("dev/monocle/client/utils/player/ChatUtils"))
                    .map(i -> i.name().stringValue())
                    .filter(n -> List.of("sendMsg", "info", "infoPrefix", "warning", "warningPrefix", "error", "errorPrefix").contains(n)).toList();
                String path = root.relativize(file).toString().replace('\\', '/');
                assert directChat.isEmpty() || List.of("Module.class", "misc/BetterChat.class", "misc/CommunityChat.class").contains(path)
                    : "Unmigrated direct module chat: " + path + " " + directChat;
            }
        }
        assert audited > 200 : "Audit must cover the compiled module tree";
        System.out.println("Notification feed checks passed: all-module feedback routing; audited " + audited + " compiled classes, preserving IRC and actionable confirmation exceptions.");
    }
}
