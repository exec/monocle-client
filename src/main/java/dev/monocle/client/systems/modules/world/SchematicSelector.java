package dev.monocle.client.systems.modules.world;

import com.mojang.blaze3d.platform.InputConstants;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.StringSetting;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.utils.network.MonocleExecutor;
import dev.monocle.client.utils.misc.input.Input;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.world.LitematicExporter;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Monocle's read-only area selector. The wand exists only in render calls, never in Inventory. */
public class SchematicSelector extends Module {
    private static final Color PINK = new Color(255, 90, 190, 255);
    private static final Color CORNER_FILL = new Color(255, 90, 190, 65);
    private static final int BLOCKS_PER_TICK = 16_384;

    private final Setting<String> exportName = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("schematic-name")
        .description("File name inside this Minecraft instance's schematics folder. Existing files are never overwritten.")
        .defaultValue("selection")
        .build());

    private ItemStack wand = ItemStack.EMPTY;
    private ClientLevel selectionWorld;
    private BlockPos pos1, pos2;
    private int wandSlot = -1;
    private boolean firstHeld, secondHeld, writing;
    private LitematicExporter.Capture capture;
    private String captureName;
    private String status = "Enable to equip the client-only selection wand.";
    private WLabel statusLabel, cornersLabel;

    public SchematicSelector() {
        super(Categories.World, "schematic-selector", "Select two pink corners with a client-only wooden pickaxe and export a Litematica schematic.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) return;
        if (selectionWorld != mc.level) resetSelection();
        selectionWorld = mc.level;
        equipWand();
    }

    public void equipWand() {
        if (mc.player == null || mc.level == null) { error("Join a world before using the selector."); return; }
        if (!isActive()) { enable(); return; }
        if (selectionWorld != mc.level) { resetSelection(); selectionWorld = mc.level; }
        // Item components are not bound during module registration; create the visual tool only in-world.
        wand = new ItemStack(Items.WOODEN_PICKAXE);
        wand.set(DataComponents.CUSTOM_NAME, Component.literal("Schematic Selector").withStyle(ChatFormatting.LIGHT_PURPLE));
        wandSlot = mc.player.getInventory().getSelectedSlot();
        firstHeld = secondHeld = false;
        mc.gameMode.stopDestroyBlock();
        if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        setStatus("Wand in hotbar slot " + (wandSlot + 1) + ". Left: pos1; right: pos2.");
        info("Client-only wooden pickaxe equipped. Left-click: pos1; right-click: pos2. Export: %sschematic export <name>. Your real item is unchanged.", Config.get().prefix.get());
    }

    @Override
    public void onDeactivate() {
        wandSlot = -1;
        wand = ItemStack.EMPTY;
        firstHeld = secondHeld = false;
        cancelCapture();
        resetSelection();
        setStatus(writing ? "Snapshot complete; the file save is finishing." : "Selector disabled; selection cleared.");
        statusLabel = cornersLabel = null;
    }

    private static SchematicSelector activeSelector() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        SchematicSelector selector = modules.get(SchematicSelector.class);
        return selector != null && selector.isActive() && selector.mc.player != null
            && selector.selectionWorld == selector.mc.level && selector.selectionWorld != null ? selector : null;
    }

    public static boolean isHoldingWand() {
        SchematicSelector selector = activeSelector();
        return selector != null && wandSelected(selector.wandSlot, selector.mc.player.getInventory().getSelectedSlot());
    }

    static boolean wandSelected(int wandSlot, int selectedSlot) {
        return wandSlot >= 0 && wandSlot < 9 && wandSlot == selectedSlot;
    }

    public static ItemStack renderWand(ItemStack original, int hotbarSlot) {
        SchematicSelector selector = activeSelector();
        return selector != null && wandSelected(selector.wandSlot, hotbarSlot) ? selector.wand : original;
    }

    public static ItemStack renderMainHand(ItemStack original) {
        return isHoldingWand() ? activeSelector().wand : original;
    }

    /** Runs before module input listeners. The fake tool never becomes an inventory stack or a use packet. */
    public static boolean handleWandInput(InputConstants.Key key, boolean pressed) {
        if (!isHoldingWand()) return false;
        SchematicSelector selector = activeSelector();
        if (selector.mc.gui.screen() != null) return false;
        var options = selector.mc.options;
        boolean attack = options.keyAttack.matches(key), use = options.keyUse.matches(key);
        boolean consumed = false;
        for (KeyMapping binding : new KeyMapping[] { options.keyAttack, options.keyUse, options.keyPickItem, options.keyDrop, options.keySwapOffhand }) {
            if (!binding.matches(key)) continue;
            binding.setDown(false);
            while (binding.consumeClick()) { }
            consumed = true;
        }
        if (!consumed) return false;
        if (!pressed) {
            if (attack) selector.firstHeld = false;
            if (use) selector.secondHeld = false;
        } else if (attack) handleWandClick(true);
        else if (use) handleWandClick(false);
        return true;
    }

    /** Consume misses and entity clicks too: the real item underneath must never be used by the wand. */
    public static boolean handleWandClick(boolean firstCorner) {
        if (!isHoldingWand()) return false;
        SchematicSelector selector = activeSelector();
        if (selector.mc.gui.screen() != null) return true;
        if (!Input.isPressed(firstCorner ? selector.mc.options.keyAttack : selector.mc.options.keyUse)) return true;
        if (firstCorner ? selector.firstHeld : selector.secondHeld) return true;
        if (firstCorner) selector.firstHeld = true;
        else selector.secondHeld = true;
        if (selector.mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK)
            selector.selectCorner(firstCorner, hit.getBlockPos());
        return true;
    }

    public boolean selectCorner(boolean firstCorner, BlockPos position) {
        if (!isActive() || mc.player == null || mc.level == null) { error("Enable Schematic Selector first."); return false; }
        if (isBusy()) { warning("Finish or cancel the current capture before changing the selection."); return false; }
        if (selectionWorld != mc.level) { resetSelection(); selectionWorld = mc.level; }
        if (!mc.level.isInWorldBounds(position)) { error("That corner is outside this world's bounds."); return false; }
        if (position.equals(firstCorner ? pos1 : pos2)) return true;
        if (firstCorner) pos1 = position.immutable();
        else pos2 = position.immutable();
        setStatus(selectionSummary());
        info("Pos %d: %s. %s", firstCorner ? 1 : 2, position.toShortString(), selectionSummary());
        return true;
    }

    public boolean export(String name) {
        if (!isActive() || mc.player == null || mc.level == null || selectionWorld != mc.level) {
            error("Enable Schematic Selector and select both corners in this world first.");
            return false;
        }
        if (isBusy()) { warning("An export is already running."); return false; }
        if (pos1 == null || pos2 == null) { error("Select both corners before exporting."); return false; }
        try {
            capture = LitematicExporter.begin(mc.level, pos1, pos2, name, mc.player.getGameProfile().name());
            captureName = name;
            setStatus("Capturing " + capture.volume() + " blocks...");
            info("%s Captures client-visible blocks and block entities only. Entities/ticks are omitted; container contents are not guaranteed.", status);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            setStatus(e.getMessage());
            error("Export not started: %s", e.getMessage());
            return false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level != selectionWorld || mc.player == null) {
            cancelCapture();
            resetSelection();
            wandSlot = -1;
            setStatus("World changed. Re-equip the wand and select new corners.");
            return;
        }
        if (!Input.isPressed(mc.options.keyAttack)) firstHeld = false;
        if (!Input.isPressed(mc.options.keyUse)) secondHeld = false;
        if (capture == null) return;
        try {
            capture.step(BLOCKS_PER_TICK);
            setStatus("Capturing: " + capture.captured() + " / " + capture.volume() + " blocks");
            if (!capture.done()) return;
            CompoundTag snapshot = capture.finish();
            capture = null;
            String name = captureName;
            Path directory = mc.gameDirectory.toPath().resolve("schematics");
            ClientLevel sourceWorld = selectionWorld;
            writing = true;
            setStatus("Saving " + name + ".litematic...");
            // Only the completed, detached NBT crosses threads. No world or inventory reads in this task.
            MonocleExecutor.execute(() -> {
                try {
                    Path file = LitematicExporter.write(directory, name, snapshot);
                    mc.execute(() -> {
                        writing = false;
                        if (selectionWorld == sourceWorld && isActive()) setStatus("Saved " + file.getFileName());
                        if (mc.player != null && mc.level == sourceWorld) info("Saved schematic: %s", file);
                    });
                } catch (IOException | RuntimeException e) {
                    mc.execute(() -> {
                        writing = false;
                        if (selectionWorld == sourceWorld && isActive()) setStatus("Export failed: " + e.getMessage());
                        if (mc.player != null && mc.level == sourceWorld) error("Export failed: %s", e.getMessage());
                    });
                }
            });
        } catch (RuntimeException e) {
            capture = null;
            writing = false;
            setStatus("Export failed: " + e.getMessage());
            error("%s", status);
        }
    }

    public void cancelCapture() {
        if (capture != null) { capture = null; setStatus("Capture cancelled. No schematic was written."); }
        else if (writing) setStatus("Snapshot complete; the file save is finishing.");
    }

    public void clearSelection() {
        if (writing) { warning("The completed snapshot is still saving; it will not be deleted."); }
        cancelCapture();
        resetSelection();
        if (mc.player != null) selectionWorld = mc.level;
        setStatus("Selection cleared.");
    }

    private void resetSelection() {
        pos1 = pos2 = null;
        selectionWorld = null;
    }

    public boolean isBusy() { return capture != null || writing; }
    public String getStatus() { return status; }

    private String selectionSummary() {
        if (pos1 == null || pos2 == null) return "Select " + (pos1 == null ? "pos1" : "pos2") + ".";
        long x = Math.abs((long) pos1.getX() - pos2.getX()) + 1;
        long y = Math.abs((long) pos1.getY() - pos2.getY()) + 1;
        long z = Math.abs((long) pos1.getZ() - pos2.getZ()) + 1;
        return x + " x " + y + " x " + z;
    }

    private void setStatus(String text) {
        status = text;
        if (statusLabel != null) statusLabel.set(text);
        if (cornersLabel != null) cornersLabel.set("Pos1: " + (pos1 == null ? "unset" : pos1.toShortString())
            + " | Pos2: " + (pos2 == null ? "unset" : pos2.toShortString()));
    }

    @Override
    public String getInfoString() { return capture != null ? capture.captured() * 100L / capture.volume() + "%" : selectionSummary(); }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        list.add(theme.label("Left-click: pos1 | Right-click: pos2 | Pink outline includes both corners."));
        list.add(theme.label("Your real inventory is unchanged. Switch slots for normal item use."));
        cornersLabel = list.add(theme.label("")).widget();
        statusLabel = list.add(theme.label(status)).widget();
        WButton equip = list.add(theme.button("Equip Selection Wand")).expandX().widget();
        equip.action = this::equipWand;
        list.add(theme.button("Export Schematic")).expandX().widget().action = () -> export(exportName.get());
        list.add(theme.button("Cancel Capture")).expandX().widget().action = this::cancelCapture;
        list.add(theme.button("Clear Selection")).expandX().widget().action = this::clearSelection;
        list.add(theme.button("Open Schematics Folder")).expandX().widget().action = () -> {
            Path directory = mc.gameDirectory.toPath().resolve("schematics");
            try {
                Files.createDirectories(directory);
                Util.getPlatform().openPath(directory);
            } catch (IOException e) { error("Cannot open schematics folder: %s", e.getMessage()); }
        };
        list.add(theme.label("Command: " + Config.get().prefix.get() + "schematic export <name>"));
        setStatus(status);
        return list;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level != selectionWorld) return;
        if (pos1 != null) event.renderer.box(pos1, CORNER_FILL, PINK, ShapeMode.Both, 0);
        if (pos2 != null) event.renderer.box(pos2, CORNER_FILL, PINK, ShapeMode.Both, 0);
        if (pos1 != null && pos2 != null) event.renderer.box(
            Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()),
            (double) Math.max(pos1.getX(), pos2.getX()) + 1, (double) Math.max(pos1.getY(), pos2.getY()) + 1, (double) Math.max(pos1.getZ(), pos2.getZ()) + 1,
            Color.CLEAR, PINK, ShapeMode.Lines, 0);
    }
}
