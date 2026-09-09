package dev.monocle.client.modintegration;

import dev.monocle.client.mixin.printer.ActionHandlerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Optional, client-thread-only coordination; Sakura remains responsible for every placement. */
public final class PrinterIntegration {
    private static Backend session;

    private PrinterIntegration() { }

    public static String availabilityError() {
        return PrinterMixinPlugin.dependencyError();
    }

    /** A null return means success. Never takes ownership of a pre-existing placement job. */
    public static String begin() {
        if (session != null) return "Printer Helper already owns a printing session.";
        String error = availabilityError();
        if (error != null) return error;
        try {
            error = Backend.startError();
            if (error != null) return error;
            Backend next = new Backend();
            if (next.bounds.isEmpty()) return "The enabled schematic has no blocks inside the active render layers.";
            next.enableMode();
            session = next;
            return null;
        } catch (LinkageError incompatible) {
            return "The installed Sakura mods do not expose the supported Printer API. Install the listed 26.2 releases (" + incompatible.getClass().getSimpleName() + ").";
        }
    }

    public static void close() {
        if (session == null) return;
        Backend old = session;
        old.printing = false;
        try {
            old.cancelOwnedActions();
        } finally {
            try {
                old.restoreMode();
            } finally {
                session = null;
            }
        }
    }

    public static void setPrinting(boolean printing) {
        if (session == null) return;
        session.printing = printing;
        if (!printing && session.idle()) session.restoreControls();
    }

    /** Immediate user/safety pause: abandon only this session's queued work before releasing movement. */
    public static void suspend() {
        if (session == null) return;
        session.printing = false;
        session.cancelOwnedActions();
        session.job = null;
        session.failure = null;
    }

    /** Change escape-corridor reservations only between jobs, never partway through a placement. */
    public static boolean setPlacementFilter(Predicate<BlockPos> filter) {
        Objects.requireNonNull(filter);
        if (session == null || !session.idle()) return false;
        session.filter = filter;
        session.job = null;
        return true;
    }

    public static boolean isIdle() {
        return session == null || session.idle();
    }

    public static String sessionError() {
        return session == null ? "Printer Helper has no active session." : session.error();
    }

    public static double printingReach() {
        return availabilityError() == null ? Backend.reach() : 5;
    }

    public static boolean printingInAir() {
        return session != null && Backend.inAir();
    }

    public static List<AABB> placementBounds() {
        return session == null ? List.of() : session.bounds;
    }

    /** Enabled regions without render-layer clipping, for finding a real exit from the complete build. */
    public static List<AABB> fullPlacementBounds() {
        return session == null ? List.of() : session.fullBounds;
    }

    public static boolean isInScope(BlockPos pos) {
        return session != null && contains(session.bounds, pos);
    }

    public static boolean ready(BlockPos pos) {
        return isInScope(pos) && session.ready(pos);
    }

    /** Null means unavailable/outside scope, never an assumed air block. */
    public static BlockState desiredState(BlockPos pos) {
        return ready(pos) ? session.desired(pos) : null;
    }

    public static List<ItemStack> requiredItems(BlockPos pos) {
        return ready(pos) ? session.items(pos) : List.of();
    }

    public static boolean matches(BlockPos pos) {
        BlockState desired = desiredState(pos);
        return desired != null && session.matches(pos, desired);
    }

    /** An eligibility probe, not a placement attempt. Call at a stationary printing perch. */
    public static boolean canPrint(BlockPos pos) {
        return allowPlacement(pos) && session.canPrint(pos);
    }

    public static boolean allowPlacement(BlockPos pos) {
        return ready(pos) && session.filter.test(pos);
    }

    public static boolean beginJob(Object printer, BlockPos pos) {
        if (session == null) return true;
        if (!ownsPrinter(printer) || !allowPlacement(pos)) return false;
        session.job = pos.immutable();
        session.pendingControls = true;
        return true;
    }

    // These hooks are no-ops unless this exact session owns the optional printer.
    public static boolean ownsPrinter(Object printer) {
        return session != null && session.owns(printer);
    }

    public static boolean allowNewJobs(Object printer) {
        if (session == null) return true;
        if (session.error() != null) {
            session.printing = false;
            session.cancelOwnedActions();
            return false;
        }
        return session.owns(printer) && session.printing && Minecraft.getInstance().gui.screen() == null;
    }

    public static boolean beforeActionTick(Object handler) {
        if (session == null || !session.handles(handler)) return true;
        if (session.idle()) session.job = null;
        if (session.error() == null && !session.idle() && session.hasOpenScreen())
            session.failure = "A screen or container opened during a queued placement. Close it, then resume Printer Helper.";
        if (session.error() == null && (session.job == null || allowPlacement(session.job))) return true;
        if (session.error() == null) session.failure = "The queued Printer target is no longer loaded or safe for the reserved escape path. Restart Printer Helper.";
        session.printing = false;
        session.cancelOwnedActions();
        return false;
    }

    public static void afterActionTick(Object handler) {
        if (session != null && session.handles(handler) && !session.printing && session.idle()) session.restoreControls();
    }

    static boolean contains(List<AABB> boxes, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        return boxes.stream().anyMatch(box -> box.contains(center));
    }

    static AABB clippedBox(BlockPos first, BlockPos second, Direction.Axis axis, int layerMin, int layerMax) {
        double minX = Math.min(first.getX(), second.getX()), maxX = (double) Math.max(first.getX(), second.getX()) + 1;
        double minY = Math.min(first.getY(), second.getY()), maxY = (double) Math.max(first.getY(), second.getY()) + 1;
        double minZ = Math.min(first.getZ(), second.getZ()), maxZ = (double) Math.max(first.getZ(), second.getZ()) + 1;
        switch (axis) {
            case X -> { minX = Math.max(minX, layerMin); maxX = Math.min(maxX, (double) layerMax + 1); }
            case Y -> { minY = Math.max(minY, layerMin); maxY = Math.min(maxY, (double) layerMax + 1); }
            case Z -> { minZ = Math.max(minZ, layerMin); maxZ = Math.min(maxZ, (double) layerMax + 1); }
        }
        return minX < maxX && minY < maxY && minZ < maxZ ? new AABB(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    /** All optional-class references stay here; ordinary module loading never initializes this class. */
    private static final class Backend {
        private final Minecraft mc = Minecraft.getInstance();
        private final ClientLevel level = mc.level;
        private final me.aleksilassila.litematica.printer.Printer printer = me.aleksilassila.litematica.printer.LitematicaMixinMod.printer;
        private final fi.dy.masa.litematica.world.WorldSchematic world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        private final fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement = enabledPlacements().getFirst();
        private final String placementStamp = placementStamp(placement);
        private final String layerStamp = layerStamp();
        private final List<AABB> bounds = bounds(placement, true);
        private final List<AABB> fullBounds = bounds(placement, false);
        private final boolean previousMode = me.aleksilassila.litematica.printer.config.Configs.PRINT_MODE.getBooleanValue();
        private boolean printing;
        private Predicate<BlockPos> filter = pos -> true;
        private BlockPos job;
        private String failure;
        private boolean pendingControls;

        private static String startError() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return "Join a world before starting Printer Helper.";
            var printer = me.aleksilassila.litematica.printer.LitematicaMixinMod.printer;
            if (printer == null || printer.player != mc.player) return "Wait for Litematica Printer to initialize, then start Printer Helper.";
            if (!(printer.actionHandler instanceof ActionHandlerAccessor)) return "Printer Helper's optional compatibility hooks did not load.";
            if (!printer.actionHandler.acceptsActions() || printer.actionHandler.lookAction != null)
                return "Wait for the current Printer action to finish, then start Printer Helper.";
            if (fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld() == null) return "Load a schematic in Litematica first.";
            if (enabledPlacements().size() != 1) return "Enable exactly one schematic placement in Litematica before starting Printer Helper.";
            return null;
        }

        private static List<fi.dy.masa.litematica.schematic.placement.SchematicPlacement> enabledPlacements() {
            return fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().stream()
                .filter(fi.dy.masa.litematica.schematic.placement.SchematicPlacement::isEnabled).toList();
        }

        private static List<AABB> bounds(fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement, boolean clipLayers) {
            var layer = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
            List<AABB> result = new ArrayList<>();
            for (var box : placement.getSubRegionBoxes(fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
                if (box.getPos1() == null || box.getPos2() == null) continue;
                AABB clipped = clippedBox(box.getPos1(), box.getPos2(), layer.getAxis(),
                    clipLayers ? layer.getMinLayerBoundary() : Integer.MIN_VALUE, clipLayers ? layer.getMaxLayerBoundary() : Integer.MAX_VALUE);
                if (clipped != null) result.add(clipped);
            }
            return List.copyOf(result);
        }

        private static String placementStamp(fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement) {
            StringBuilder result = new StringBuilder().append(placement.getOrigin()).append(placement.getRotation()).append(placement.getMirror());
            placement.getEnabledRelativeSubRegionPlacements().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                var region = entry.getValue();
                result.append('|').append(entry.getKey()).append(region.getPos()).append(region.getRotation()).append(region.getMirror());
            });
            return result.toString();
        }

        private static String layerStamp() {
            var layer = fi.dy.masa.litematica.data.DataManager.getRenderLayerRange();
            return layer.getLayerMode() + ":" + layer.getAxis() + ":" + layer.getMinLayerBoundary() + ":" + layer.getMaxLayerBoundary()
                + ":" + layer.shouldFollowPlayer();
        }

        private String error() {
            if (failure != null) return failure;
            if (mc.level != level || mc.player != printer.player || me.aleksilassila.litematica.printer.LitematicaMixinMod.printer != printer)
                return "The player, world or Printer instance changed. Restart Printer Helper.";
            if (fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld() != world)
                return "The schematic world changed. Restart Printer Helper.";
            var enabled = enabledPlacements();
            if (enabled.size() != 1 || enabled.getFirst() != placement || !placementStamp.equals(placementStamp(placement)) || !fullBounds.equals(bounds(placement, false)))
                return "The schematic placement or enabled subregions changed. Restart Printer Helper.";
            if (!layerStamp.equals(layerStamp())) return "The active Litematica render layers changed. Restart Printer Helper.";
            return null;
        }

        private boolean ready(BlockPos pos) {
            return mc.level == level && mc.player == printer.player && fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld() == world
                && !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos)
                && world.getChunkSource().getChunkState(pos.getX() >> 4, pos.getZ() >> 4).atLeast(fi.dy.masa.litematica.world.ChunkSchematicState.FILLED);
        }

        private BlockState desired(BlockPos pos) { return world.getBlockState(pos); }
        private boolean matches(BlockPos pos, BlockState desired) { return desired.equals(level.getBlockState(pos)); }
        private boolean owns(Object candidate) { return printer == candidate; }
        private boolean handles(Object candidate) { return printer.actionHandler == candidate; }
        private boolean hasOpenScreen() { return mc.gui.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu; }

        private List<ItemStack> items(BlockPos pos) {
            var state = world.getBlockState(pos);
            var cache = fi.dy.masa.litematica.materials.MaterialCache.getInstance();
            var items = cache.requiresMultipleItems(state) ? cache.getItems(state) : List.of(cache.getRequiredBuildItemForState(state, world, pos));
            return items.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        }

        private boolean canPrint(BlockPos pos) {
            double reach = reach();
            Vec3 center = Vec3.atCenterOf(pos);
            double eyes = printer.player.getEyePosition().distanceToSqr(center);
            if (eyes > reach * reach || eyes <= 1 || printer.player.position().distanceToSqr(center) <= 1
                || !printer.player.getAbilities().mayBuild || !me.aleksilassila.litematica.printer.config.Configs.INTERACT_BLOCKS.getBooleanValue()) return false;
            var state = new me.aleksilassila.litematica.printer.SchematicBlockState(level, world, pos);
            if (state.targetState.isAir() || state.targetState.equals(state.currentState)) return false;
            for (var guide : new me.aleksilassila.litematica.printer.guides.Guides().getInteractionGuides(state)) {
                if (guide.canExecute(printer.player)) return true;
                if (guide.skipOtherGuides()) return false;
            }
            return false;
        }

        private static double reach() {
            return me.aleksilassila.litematica.printer.config.Configs.PRINTING_RANGE.getDoubleValue();
        }

        private static boolean inAir() {
            return me.aleksilassila.litematica.printer.config.Configs.PRINT_IN_AIR.getBooleanValue();
        }

        private boolean idle() {
            return printer.actionHandler.acceptsActions() && printer.actionHandler.lookAction == null;
        }

        private void enableMode() {
            me.aleksilassila.litematica.printer.config.Configs.PRINT_MODE.setBooleanValue(true);
        }

        private void restoreMode() {
            // Preserve a user's subsequent change; we only own the true value assigned at begin().
            if (me.aleksilassila.litematica.printer.config.Configs.PRINT_MODE.getBooleanValue())
                me.aleksilassila.litematica.printer.config.Configs.PRINT_MODE.setBooleanValue(previousMode);
        }

        private void cancelOwnedActions() {
            ((ActionHandlerAccessor) printer.actionHandler).monocle$getActionQueue().clear();
            printer.actionHandler.lookAction = null;
            restoreControls();
        }

        private void restoreControls() {
            if (!pendingControls) return;
            pendingControls = false;
            if (mc.level != level || mc.player != printer.player || mc.getConnection() == null) return;
            Input current = mc.player.input.keyPresses;
            Input restored = new Input(current.forward(), current.backward(), current.left(), current.right(), current.jump(), mc.options.keyShift.isDown(), current.sprint());
            mc.player.input.keyPresses = restored;
            mc.player.connection.send(new ServerboundPlayerInputPacket(restored));
            // Clear lookAction before constructing this packet: Sakura's packet mixin otherwise replaces its angles.
            mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), mc.player.horizontalCollision));
        }
    }
}
