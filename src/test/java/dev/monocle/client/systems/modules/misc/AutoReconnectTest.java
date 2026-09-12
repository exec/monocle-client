package dev.monocle.client.systems.modules.misc;

import net.minecraft.network.chat.Component;
import java.util.List;

/** ./gradlew autoReconnectCheck */
public final class AutoReconnectTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Assertions required");
        assert AutoReconnect.retryDelay(3.5, 30, 0, true) == 3.5;
        assert AutoReconnect.retryDelay(3.5, 30, 1, true) == 7;
        assert AutoReconnect.retryDelay(3.5, 30, 2, true) == 14;
        assert AutoReconnect.retryDelay(3.5, 30, Integer.MAX_VALUE, true) == 30;
        assert AutoReconnect.retryDelay(3.5, 30, 20, false) == 3.5;
        assert AutoReconnect.retryDelay(40, 30, 0, true) == 40;
        assert AutoReconnect.retryDelay(0, 30, 0, false) == 1;
        assert Double.isFinite(AutoReconnect.retryDelay(Double.NaN, Double.NaN, 0, true));
        assert AutoReconnect.retryDelay(Double.MAX_VALUE, 30, 0, true) == 600;
        assert AutoReconnect.matchesReason("Account SUSPENDED", List.of("", "  suspended "));
        assert !AutoReconnect.matchesReason("Timed out", List.of("  ", "banned"));
        assert AutoReconnect.permanentError(Component.translatable("disconnect.loginFailedInfo",
            Component.translatable("disconnect.loginFailedInfo.invalidSession")));
        assert AutoReconnect.permanentError(Component.literal("Error: ").append(Component.translatable("multiplayer.disconnect.banned.reason", "test")));
        assert AutoReconnect.permanentError(Component.translatable("multiplayer.disconnect.duplicate_login"));
        assert !AutoReconnect.permanentError(Component.translatable("disconnect.timeout"));
        assert !AutoReconnect.permanentError(Component.translatable("multiplayer.disconnect.authservers_down"));
        assert !AutoReconnect.permanentError(Component.literal("Custom server message"));
        try (var bytes = AutoReconnect.class.getResourceAsStream("/dev/monocle/client/mixin/DisconnectedScreenMixin.class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var tick = model.methods().stream().filter(m -> m.methodName().equalsString("tick")).findFirst().orElseThrow();
            var calls = tick.code().orElseThrow().elementList().stream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
            assert calls.containsAll(List.of("screen", "isActive", "stopReason", "nanoTime", "tryConnecting"));
            assert calls.indexOf("stopReason") < calls.indexOf("tryConnecting");
        }
        System.out.println("Auto Reconnect checks passed: adaptive/fixed delay, bounds, nested error keys, phrase filters and screen retry guards.");
    }
}
