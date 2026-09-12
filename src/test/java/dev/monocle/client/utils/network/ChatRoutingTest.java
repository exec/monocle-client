package dev.monocle.client.utils.network;

import dev.monocle.client.systems.modules.misc.CommunityChat;
import java.util.List;

/** ./gradlew chatRoutingCheck; routing never depends on connection availability. */
public final class ChatRoutingTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        assert CommunityChat.routeToIrc(true, "hello", ".", "#");
        assert !CommunityChat.routeToIrc(false, "hello", ".", "#");
        for (String local : List.of("/spawn", "/msg friend hello", ".chat game", ".irc disconnect", "#stop", "", "   "))
            assert !CommunityChat.routeToIrc(true, local, ".", "#") : local;
        assert !CommunityChat.routeToIrc(true, "!chat game", "!", "");
        assert CommunityChat.routeToIrc(true, "#hello", ".", "") : "No pathing prefix when Baritone is absent";
        try (var bytes = CommunityChat.class.getResourceAsStream("/dev/monocle/client/mixin/ChatScreenMixin.class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var route = model.methods().stream().filter(m -> m.methodName().equalsString("routeTypedChat")).findFirst().orElseThrow();
            var calls = route.code().orElseThrow().elementList().stream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
            assert calls.indexOf("cancel") < calls.indexOf("say") : "Cancel game transmission before attempting IRC";
            assert !calls.contains("sendChat") && !calls.contains("sendCommand") : "Never fall back into game chat";
        }
        System.out.println("Chat routing checks passed: game/IRC selection, slash/local/pathing commands, and fail-closed sending.");
    }
}
