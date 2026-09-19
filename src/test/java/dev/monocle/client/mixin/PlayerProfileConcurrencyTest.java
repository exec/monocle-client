package dev.monocle.client.mixin;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Check the installed map replacement and concurrent lookup semantics without launching Minecraft. */
public final class PlayerProfileConcurrencyTest {
    public static void main(String[] args) throws Exception {
        try (var bytes = PlayerProfileConcurrencyTest.class.getResourceAsStream("ClientPacketListenerMixin.class")) {
            var model = ClassFile.of().parse(bytes.readAllBytes());
            var patch = model.methods().stream().filter(m -> m.methodName().equalsString("monocle$threadSafePlayerProfiles")).findFirst().orElseThrow();
            var code = patch.code().orElseThrow().elementList();
            assert code.stream().anyMatch(e -> e instanceof InvokeInstruction call
                && call.owner().asInternalName().equals("java/util/concurrent/ConcurrentHashMap") && call.name().equalsString("<init>"));
            assert code.stream().anyMatch(e -> e instanceof FieldInstruction write
                && write.opcode() == Opcode.PUTFIELD && write.name().equalsString("playerInfoMap"));
        }
        Map<Integer, String> initial = new HashMap<>(); initial.put(0, "ExistingPlayer");
        Map<Integer, String> players = new ConcurrentHashMap<>(initial);
        assert players.get(0).equals("ExistingPlayer") : "Preserve existing entries";
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 1; i < 100_000; i++) { players.put(i % 64 + 1, "Player"); players.remove((i + 1) % 64 + 1); }
            } catch (Throwable t) { failure.set(t); }
        });
        writer.start(); start.countDown();
        try {
            for (int i = 0; i < 10_000; i++) {
                boolean found = false;
                for (String name : players.values()) if (name.equalsIgnoreCase("existingplayer")) found = true;
                assert found : "Concurrent updates must not lose stable profiles";
            }
        } finally { writer.join(); }
        assert failure.get() == null : failure.get();
        System.out.println("Player profile checks passed: compiled map replacement, retained entries and concurrent name lookups.");
    }
}
