package dev.monocle.client.systems.modules.world;

import dev.monocle.client.utils.world.PrinterFlight;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Small, client-independent geometry rules shared by printing and its checks. */
public final class PrinterSafety {
    private PrinterSafety() {}

    public static AABB enclosing(List<AABB> boxes) {
        if (boxes.isEmpty()) throw new IllegalArgumentException("Enable a nonempty schematic placement first.");
        AABB result = boxes.getFirst();
        for (AABB box : boxes) result = result.minmax(box);
        return result;
    }

    public static double depth(Vec3 position, AABB bounds) {
        return Math.max(0, Math.min(Math.min(position.x - bounds.minX, bounds.maxX - position.x),
            Math.min(Math.min(position.y - bounds.minY, bounds.maxY - position.y),
                Math.min(position.z - bounds.minZ, bounds.maxZ - position.z))));
    }

    public static List<AABB> corridor(List<Vec3> route, double width, double height) {
        List<AABB> boxes = new ArrayList<>();
        for (int i = 0; i < route.size(); i++) {
            AABB box = PrinterFlight.body(route.get(i), width, height);
            if (i > 0) box = box.minmax(PrinterFlight.body(route.get(i - 1), width, height));
            boxes.add(box.inflate(.2));
        }
        return List.copyOf(boxes);
    }

    /** Reserve neighboring cells too: doors and other multi-block placements can occupy an extra cell. */
    public static boolean mayPlace(BlockPos position, List<AABB> protectedCorridor, AABB player) {
        return mayPlace(position, protectedCorridor, player, false, false, 0);
    }

    public static boolean mayPlace(BlockPos position, List<AABB> protectedCorridor, AABB player, boolean falling, boolean fluid, int minY) {
        // ponytail: do not simulate fluid/redstone physics; reject fluids and reserve a falling block's whole vertical column.
        if (fluid) return false;
        AABB affected = new AABB(position).inflate(1.01);
        if (falling) affected = new AABB(affected.minX, Math.min(minY, affected.minY), affected.minZ, affected.maxX, affected.maxY, affected.maxZ);
        if (affected.intersects(player.inflate(.2))) return false;
        for (AABB protectedBox : protectedCorridor) if (affected.intersects(protectedBox)) return false;
        return true;
    }

    public static boolean touches(BlockPos position, List<AABB> boxes) {
        AABB block = new AABB(position);
        return boxes.stream().anyMatch(block::intersects);
    }

    /** Streams bounds instead of allocating every position of a large schematic. Max edges are exclusive. */
    public static final class Scan implements Iterator<BlockPos> {
        private final List<AABB> boxes;
        private int boxIndex, x, y, z, minX, minY, minZ, maxX, maxY, maxZ;
        private boolean ready;

        public Scan(List<AABB> boxes) {
            this.boxes = List.copyOf(boxes);
            advanceBox();
        }

        private void advanceBox() {
            ready = false;
            while (boxIndex < boxes.size()) {
                AABB box = boxes.get(boxIndex++);
                if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                    || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)
                    || Math.abs(box.minX) > 30_000_000 || Math.abs(box.maxX) > 30_000_000
                    || Math.abs(box.minZ) > 30_000_000 || Math.abs(box.maxZ) > 30_000_000
                    || Math.abs(box.minY) > 30_000_000 || Math.abs(box.maxY) > 30_000_000)
                    throw new IllegalArgumentException("Schematic bounds are invalid.");
                minX = Mth.floor(box.minX); minY = Mth.floor(box.minY); minZ = Mth.floor(box.minZ);
                maxX = Mth.ceil(box.maxX) - 1; maxY = Mth.ceil(box.maxY) - 1; maxZ = Mth.ceil(box.maxZ) - 1;
                if (maxX < minX || maxY < minY || maxZ < minZ) continue;
                x = minX; y = minY; z = minZ;
                ready = true;
                return;
            }
        }

        @Override public boolean hasNext() { return ready; }

        @Override public BlockPos next() {
            if (!ready) throw new NoSuchElementException();
            BlockPos result = new BlockPos(x, y, z);
            if (++x > maxX) {
                x = minX;
                if (++z > maxZ) {
                    z = minZ;
                    if (++y > maxY) advanceBox();
                }
            }
            return result;
        }
    }
}
