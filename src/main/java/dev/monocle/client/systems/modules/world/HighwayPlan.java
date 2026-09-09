package dev.monocle.client.systems.modules.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Geometry and bounded walking shared by the builder, preview and regression check. */
final class HighwayPlan {
    static final int PAVING_LOOKBACK = 2;
    record Cell(int x, int y, int z) {
        Cell add(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        double distanceSquared(Cell other) {
            return (double) (x - other.x) * (x - other.x) + (double) (y - other.y) * (y - other.y) + (double) (z - other.z) * (z - other.z);
        }
    }

    private HighwayPlan() {}

    static int left(int width) { return width / 2; }
    static int right(int width) { return (width - 1) / 2; }
    static boolean usableTool(int remaining, int maximum, int minimumPercent) {
        return maximum > 0 && remaining > maximum * (minimumPercent / 100.0);
    }

    record PavingCell(Cell position, boolean filler) {}

    static boolean withinLength(boolean diagonal, double completedDistance, int sections, int limit) {
        return limit <= 0 || completedDistance + sections * (diagonal ? Math.sqrt(2) : 1) <= limit + 0.001;
    }

    record SupplyLayout(List<Cell> positions, Cell facing, Cell approach) {}

    static SupplyLayout supplyLayout(int dx, int dz, int width, boolean doubleChest) {
        validate(dx, dz, width, 1);
        boolean diagonal = dx != 0 && dz != 0;
        int backX = diagonal ? (dz - dx) / 2 : -dx;
        int backZ = diagonal ? -(dx + dz) / 2 : -dz;
        Cell approach = doubleChest && diagonal ? new Cell(-dx, 0, -dz) : new Cell(0, 0, 0);
        Cell first = approach.add(backX, 0, backZ);
        List<Cell> positions = doubleChest && width >= 2
            ? List.of(first, first.add(-backZ, 0, backX)) : List.of(first);
        // Both chest fronts face the approach; placement yaw faces the opposite direction.
        return new SupplyLayout(positions, new Cell(-backX, 0, -backZ), approach);
    }

    static List<PavingCell> paving(int dx, int dz, int width, boolean floor, boolean railings, boolean supports) {
        List<PavingCell> result = new ArrayList<>();
        if (floor) for (Cell cell : floor(dx, dz, width)) result.add(new PavingCell(cell, false));
        if (railings && supports) for (Cell cell : railings(dx, dz, width, 1, -1)) result.add(new PavingCell(cell, true));
        if (railings) for (Cell cell : railings(dx, dz, width, 1, 0)) result.add(new PavingCell(cell, false));
        return List.copyOf(result);
    }

    // x/z are relative to the destination center. Passing it must not cause a turn back.
    static boolean passedSection(int dx, int dz, double x, double z) {
        double length = Math.hypot(dx, dz);
        return (x * dx + z * dz) / length >= -0.1 && Math.abs(x * dz - z * dx) / length < 0.3;
    }

    static int roadFeetY(double y, int roadY) {
        return Math.abs(y - roadY) <= 0.25 ? roadY : (int) Math.floor(y);
    }

    static List<Cell> floor(int dx, int dz, int width) {
        return front(dx, dz, width, 1).stream().map(p -> p.add(0, -1, 0)).toList();
    }

    static List<Cell> front(int dx, int dz, int width, int height) {
        validate(dx, dz, width, height);
        List<Cell> result = new ArrayList<>();
        if (dx != 0 && dz != 0) {
            row(result, (dx + dz) / 2 + dz * (left(width) - 1), (dz - dx) / 2 - dx * (left(width) - 1), -dz, dx, width - 1, 0, height, false);
        }
        row(result, dx + dz * left(width), dz - dx * left(width), -dz, dx, width, 0, height, false);
        return result;
    }

    static List<Cell> railings(int dx, int dz, int width, int height, int level) {
        validate(dx, dz, width, height);
        List<Cell> result = new ArrayList<>();
        boolean diagonal = dx != 0 && dz != 0;
        int lx = diagonal ? (dx + dz) / 2 + dz * left(width) : dx + dz * (left(width) + 1);
        int lz = diagonal ? (dz - dx) / 2 - dx * left(width) : dz - dx * (left(width) + 1);
        int rx = diagonal ? (dx - dz) / 2 - dz * right(width) : dx - dz * (right(width) + 1);
        int rz = diagonal ? (dz + dx) / 2 + dx * right(width) : dz + dx * (right(width) + 1);
        for (int y = level; y < (level == 1 ? height : level + 1); y++) {
            result.add(new Cell(lx, y, lz));
            result.add(new Cell(rx, y, rz));
        }
        return result;
    }

    static List<Cell> liquids(int dx, int dz, int width, int height, boolean aboveRailings) {
        validate(dx, dz, width, height);
        List<Cell> result = new ArrayList<>();
        if (dx == 0 || dz == 0) {
            int margin = left(width) + (aboveRailings ? 2 : 1);
            row(result, dx * 2 + dz * margin, dz * 2 - dx * margin, -dz, dx, width + (aboveRailings ? 4 : 2), 0, height + 1, false);
        } else {
            row(result, dx + (dx + dz) / 2 + dz * left(width), dz + (dz - dx) / 2 - dx * left(width), -dz, dx, width + 1, 0, height + 1, !aboveRailings);
            int margin = left(width) + (aboveRailings ? 1 : 0);
            row(result, dx * 2 + dz * margin, dz * 2 - dx * margin, -dz, dx, width + (aboveRailings ? 2 : 0), 0, height + 1, aboveRailings);
        }
        return result;
    }

    static List<Cell> liquidInlets(int dx, int dz, int width, int height, boolean aboveRailings) {
        var excavation = new LinkedHashSet<>(front(dx, dz, width, height));
        if (aboveRailings) excavation.addAll(railings(dx, dz, width, height, 1));
        var entrance = new HashSet<Cell>();
        for (Cell cell : excavation) entrance.add(cell.add(-dx, 0, -dz));
        var inlets = new LinkedHashSet<Cell>();
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (Cell cell : excavation) for (int[] face : faces) {
            Cell neighbor = cell.add(face[0], face[1], face[2]);
            if (!excavation.contains(neighbor) && !entrance.contains(neighbor)) inlets.add(neighbor);
        }
        return List.copyOf(inlets);
    }

    private static void row(List<Cell> result, int x, int z, int dx, int dz, int width, int bottom, int top, boolean trimTopCorners) {
        for (int w = 0; w < width; w++) {
            for (int y = bottom; y < top; y++) {
                if (trimTopCorners && y == top - 1 && (w == 0 || w == width - 1)) continue;
                result.add(new Cell(x + dx * w, y, z + dz * w));
            }
        }
    }

    private static void validate(int dx, int dz, int width, int height) {
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || dx == 0 && dz == 0 || width < 1 || width > 5 || height < 1 || height > 7 || dx != 0 && dz != 0 && width < 3)
            throw new IllegalArgumentException("Unsupported highway geometry");
    }

    // A restock/reach adjustment is local: never wander more than 12 blocks from its starting point.
    static List<Cell> route(Cell start, Cell goal, Predicate<Cell> standable, BiPredicate<Cell, Cell> stepSafe) {
        if (start.distanceSquared(goal) > 144 || !standable.test(goal)) return List.of();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        HashMap<Cell, Cell> previous = new HashMap<>();
        queue.add(start);
        previous.put(start, null);
        while (!queue.isEmpty()) {
            Cell current = queue.remove();
            if (current.equals(goal)) {
                List<Cell> path = new ArrayList<>();
                for (Cell p = goal; p != null; p = previous.get(p)) path.add(p);
                Collections.reverse(path);
                return path;
            }
            for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy : new int[] {0, 1, -1}) {
                    Cell next = current.add(offset[0], dy, offset[1]);
                    if (Math.abs(next.y - start.y) > 3 || next.distanceSquared(start) > 144 || previous.containsKey(next) || !standable.test(next) || !stepSafe.test(current, next)) continue;
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }
        return List.of();
    }
}
