package dev.monocle.client.systems.modules.world;

import dev.monocle.client.commands.Commands;
import dev.monocle.client.systems.modules.Modules;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;

/** Native assertions and compiled boundary checks; module construction requires a live Minecraft renderer. */
public final class SchematicSelectorTest {
    public static void main(String[] args) throws Exception {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) throw new IllegalStateException("Run with assertions enabled (-ea).");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        for (int wand = -2; wand <= 10; wand++) {
            for (int selected = -2; selected <= 10; selected++) {
                assert SchematicSelector.wandSelected(wand, selected) == (wand >= 0 && wand < 9 && wand == selected)
                    : "Only the selected, valid hotbar slot acts as the wand; changing slots restores normal use";
            }
        }

        ClassModel selector = compiled(SchematicSelector.class);
        int wandCreations = 0;
        for (MethodModel method : selector.methods()) for (InvokeInstruction call : calls(method)) {
            String owner = call.owner().asInternalName(), name = call.name().stringValue();
            if (owner.equals("net/minecraft/world/item/ItemStack") && name.equals("<init>")) {
                assert method.methodName().equalsString("equipWand")
                    : "Item components are not bound during module registration: construct the wand only when equipping in-world";
                wandCreations++;
            }
            if (owner.equals("net/minecraft/world/entity/player/Inventory"))
                assert name.equals("getSelectedSlot") : "The cosmetic wand must never write or substitute real inventory contents";
            assert !owner.equals("dev/monocle/client/utils/player/InvUtils") : "A render-only wand needs no inventory transaction helper";
            assert !owner.endsWith("/ServerboundSetCreativeModeSlotPacket") : "Do not send fake creative items to the server";
            if (owner.equals("net/minecraft/client/multiplayer/MultiPlayerGameMode")) {
                assert name.equals("stopDestroyBlock") || name.equals("releaseUsingItem")
                    : "Equipping may stop an existing action, but selection must not use, place, mine or transfer the underlying item";
            }
        }
        assert wandCreations == 1 : "The visual wand has exactly one in-world construction site";
        MethodModel equip = selector.methods().stream().filter(method -> method.methodName().equalsString("equipWand")).findFirst().orElseThrow();
        boolean playerChecked = false, worldChecked = false;
        int nullGuards = 0;
        for (var element : equip.code().orElseThrow().elementList()) {
            if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD) {
                if (field.name().equalsString("player")) playerChecked = true;
                if (field.name().equalsString("level")) worldChecked = true;
            }
            if (element instanceof java.lang.classfile.instruction.BranchInstruction branch
                && (branch.opcode() == Opcode.IFNULL || branch.opcode() == Opcode.IFNONNULL)) nullGuards++;
            if (element instanceof InvokeInstruction call && call.owner().asInternalName().equals("net/minecraft/world/item/ItemStack")
                && call.name().equalsString("<init>")) {
                assert playerChecked && worldChecked && nullGuards >= 2 : "Check the player and world before constructing an item stack";
            }
        }

        MethodModel tick = selector.methods().stream().filter(method -> method.methodName().equalsString("onTick")).findFirst().orElseThrow();
        List<InvokeInstruction> tickCalls = calls(tick);
        int step = -1, finish = -1, background = -1;
        for (int i = 0; i < tickCalls.size(); i++) {
            InvokeInstruction call = tickCalls.get(i);
            if (call.owner().asInternalName().endsWith("/LitematicExporter$Capture")) {
                if (call.name().equalsString("step")) step = i;
                if (call.name().equalsString("finish")) finish = i;
            }
            if (call.owner().asInternalName().equals("dev/monocle/client/utils/network/MonocleExecutor") && call.name().equalsString("execute")) background = i;
        }
        assert step >= 0 && finish > step && background > finish : "Finish the client-thread world snapshot before scheduling disk writing";

        boolean writerFound = false;
        for (MethodModel method : selector.methods()) {
            List<InvokeInstruction> invocations = calls(method);
            if (invocations.stream().noneMatch(call -> call.owner().asInternalName().endsWith("/LitematicExporter") && call.name().equalsString("write"))) continue;
            writerFound = true;
            for (InvokeInstruction call : invocations) {
                String owner = call.owner().asInternalName();
                assert !owner.equals("net/minecraft/client/multiplayer/ClientLevel") && !owner.startsWith("net/minecraft/world/level/")
                    && !owner.startsWith("net/minecraft/world/entity/") : "The background writer must not read a live world, player or inventory";
            }
        }
        assert writerFound : "Export needs a detached-snapshot disk writer";
        assert constructs(compiled(Modules.class), "dev/monocle/client/systems/modules/world/SchematicSelector") : "Register the module";
        assert constructs(compiled(Commands.class), "dev/monocle/client/commands/commands/SchematicCommand") : "Register the command and its alias";
        for (String[] hook : new String[][] {
            {"MinecraftMixin", "onStartAttack", "handleWandClick"}, {"MinecraftMixin", "onStartUseItem", "handleWandClick"},
            {"MinecraftMixin", "monocle$selectionWandMining", "isHoldingWand"}, {"MinecraftMixin", "monocle$selectionWandPick", "isHoldingWand"},
            {"MouseHandlerMixin", "onMouseButton", "handleWandInput"}, {"KeyboardHandlerMixin", "onKey", "handleWandInput"}
        }) {
            MethodModel handler = compiled("dev.monocle.client.mixin." + hook[0]).methods().stream()
                .filter(method -> method.methodName().equalsString(hook[1])).findFirst().orElseThrow();
            boolean guarded = false;
            for (InvokeInstruction call : calls(handler)) {
                if (call.owner().asInternalName().endsWith("/SchematicSelector") && call.name().equalsString(hook[2])) guarded = true;
                if (call.name().equalsString("post")) assert guarded : "Consume selector actions before normal module input listeners";
            }
            assert guarded : "Missing wand input interception";
        }
        for (String mixin : List.of("HudMixin", "ItemInHandRendererMixin", "AvatarRendererMixin")) {
            for (MethodModel method : compiled("dev.monocle.client.mixin." + mixin).methods()) {
                if (!method.methodName().stringValue().startsWith("monocle$selectionWand")) continue;
                for (var element : method.code().orElseThrow().elementList()) {
                    if (element instanceof FieldInstruction field && field.opcode() == Opcode.PUTFIELD) {
                        assert field.owner().asInternalName().startsWith("net/minecraft/client/renderer/entity/state/")
                            : "Wand rendering may change only detached avatar render state, not inventory or cached hand stacks";
                    }
                }
            }
        }
        System.out.println("Schematic selector checks passed: wand slot isolation, no fake inventory transactions, client-thread snapshot/background-write boundary and module/command registration.");
    }

    private static List<InvokeInstruction> calls(MethodModel method) {
        return method.code().stream().flatMap(code -> code.elementList().stream())
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
    }

    private static boolean constructs(ClassModel model, String owner) {
        return model.methods().stream().flatMap(method -> calls(method).stream())
            .anyMatch(call -> call.owner().asInternalName().equals(owner) && call.name().equalsString("<init>"));
    }

    private static ClassModel compiled(Class<?> type) throws IOException {
        return compiled(type.getName());
    }

    private static ClassModel compiled(String name) throws IOException {
        try (var bytes = SchematicSelector.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            if (bytes == null) throw new AssertionError("Missing compiled " + name);
            return ClassFile.of().parse(bytes.readAllBytes());
        }
    }
}
