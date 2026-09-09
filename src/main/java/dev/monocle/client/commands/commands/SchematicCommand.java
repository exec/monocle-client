package dev.monocle.client.commands.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.monocle.client.commands.Command;
import dev.monocle.client.commands.arguments.BlockPosArgumentType;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.world.SchematicSelector;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class SchematicCommand extends Command {
    public SchematicCommand() {
        super("schematic", "Select an area and export a Litematica schematic into this instance's schematics folder.", "schem");
    }

    private SchematicSelector selector() { return Modules.get().get(SchematicSelector.class); }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(_ -> {
            info("%s | %s | %s", toString("wand"), toString("export", "<name>"), toString("clear"));
            info("%s", selector().getStatus());
            return SINGLE_SUCCESS;
        });
        builder.then(literal("wand").executes(_ -> {
            selector().equipWand();
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("export").then(argument("name", StringArgumentType.greedyString()).executes(context ->
            selector().export(StringArgumentType.getString(context, "name")) ? SINGLE_SUCCESS : 0)));
        builder.then(literal("clear").executes(_ -> {
            selector().clearSelection();
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("cancel").executes(_ -> {
            selector().cancelCapture();
            info("%s", selector().getStatus());
            return SINGLE_SUCCESS;
        }));
        for (boolean first : new boolean[] {true, false}) {
            builder.then(literal(first ? "pos1" : "pos2")
                .executes(_ -> mc.player != null && selector().selectCorner(first, mc.player.blockPosition()) ? SINGLE_SUCCESS : 0)
                .then(argument("position", BlockPosArgumentType.blockPos()).executes(context ->
                    selector().selectCorner(first, BlockPosArgumentType.getBlockPos(context, "position")) ? SINGLE_SUCCESS : 0)));
        }
    }
}
