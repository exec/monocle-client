package dev.monocle.client.utils.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Bounded local flight geometry; callers supply current loaded-world collision/hazard checks. */
public final class PrinterFlight {
    // ponytail: 32-block/4096-node routes and 128-block straight escape corridors; chain local goals, add finer navigation for narrow gaps.
    public static final int MAX_DISTANCE = 32;
    public static final int MAX_NODES = 4096;
    public static final int MAX_SEGMENT = 128;
    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private PrinterFlight() {}

    public static AABB body(Vec3 feet, double width, double height) {
        dimensions(width, height);
        requirePosition(feet);
        return new AABB(feet.x - width / 2, feet.y, feet.z - width / 2,
            feet.x + width / 2, feet.y + height, feet.z + width / 2);
    }

    /** Conservative swept full-body box: never samples past thin walls or clips a diagonal corner. */
    public static boolean segmentClear(Vec3 from, Vec3 to, double width, double height, Predicate<AABB> clear) {
        requirePosition(from);
        requirePosition(to);
        if (from.distanceToSqr(to) > MAX_SEGMENT * MAX_SEGMENT) return false;
        return clear.test(body(from, width, height).minmax(body(to, width, height)));
    }

    /** Check every touched chunk, including the negative side of zero and both sides of a boundary. */
    public static boolean loaded(AABB box, BiPredicate<Integer, Integer> chunkLoaded) {
        if (!finite(box.minX) || !finite(box.minY) || !finite(box.minZ) || !finite(box.maxX) || !finite(box.maxY) || !finite(box.maxZ)
            || box.maxX <= box.minX || box.maxY <= box.minY || box.maxZ <= box.minZ
            || box.maxX - box.minX > MAX_SEGMENT + 8 || box.maxZ - box.minZ > MAX_SEGMENT + 8) return false;
        int minX = (int) Math.floor(box.minX) >> 4, maxX = (int) Math.floor(Math.nextDown(box.maxX)) >> 4;
        int minZ = (int) Math.floor(box.minZ) >> 4, maxZ = (int) Math.floor(Math.nextDown(box.maxZ)) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!chunkLoaded.test(x, z)) return false;
        }
        return true;
    }

    /** Sakura rejects positions within one block of the target at either eye or feet height. */
    public static boolean placementReach(Vec3 feet, BlockPos target, double eyeHeight, double reach) {
        requirePosition(feet);
        if (target == null || !Double.isFinite(eyeHeight) || eyeHeight < 0 || eyeHeight > 4
            || !Double.isFinite(reach) || reach <= 1 || reach > MAX_DISTANCE) return false;
        Vec3 center = Vec3.atCenterOf(target);
        double eyeDistance = feet.add(0, eyeHeight, 0).distanceToSqr(center);
        return eyeDistance <= reach * reach && eyeDistance > 1 && feet.distanceToSqr(center) > 1;
    }

    /** One no-overshoot movement, rechecked against fresh world state immediately before requesting flight. */
    public static Vec3 safeVelocity(Vec3 from, Vec3 to, double speed, double width, double height, Predicate<AABB> clear) {
        requirePosition(from);
        requirePosition(to);
        if (!Double.isFinite(speed) || speed <= 0 || speed > 1) throw new IllegalArgumentException("Flight speed must be in (0, 1] blocks/tick.");
        Vec3 delta = to.subtract(from);
        double distance = delta.length();
        Vec3 velocity = distance <= speed ? delta : delta.scale(speed / distance);
        return segmentClear(from, from.add(velocity), width, height, clear) ? velocity : Vec3.ZERO;
    }

    /** Exact destination route, including the initial position. Empty means no safe local route. */
    public static List<Vec3> route(Vec3 start, Vec3 goal, double width, double height, Predicate<AABB> clear) {
        requirePosition(start);
        requirePosition(goal);
        dimensions(width, height);
        if (start.distanceToSqr(goal) > MAX_DISTANCE * MAX_DISTANCE || !clear.test(body(start, width, height))
            || !clear.test(body(goal, width, height))) return List.of();
        if (segmentClear(start, goal, width, height, clear)) return start.equals(goal) ? List.of(copy(start)) : List.of(copy(start), copy(goal));
        List<Vec3> path = search(start, width, height, clear,
            p -> p.distanceToSqr(goal) <= 2 && segmentClear(p, goal, width, height, clear), p -> p.distanceTo(goal));
        if (path.isEmpty()) return path;
        List<Vec3> result = new ArrayList<>(path);
        if (!result.getLast().equals(goal)) result.add(copy(goal));
        return List.copyOf(result);
    }

    /** Finds a reachable printing position, not the block that the printer will fill. */
    public static List<Vec3> routeToPlacement(Vec3 start, BlockPos target, double width, double height,
                                             double eyeHeight, double reach, Predicate<AABB> clear) {
        return routeToPlacement(start, target, width, height, eyeHeight, reach, clear, point -> true);
    }

    public static List<Vec3> routeToPlacement(Vec3 start, BlockPos target, double width, double height,
                                             double eyeHeight, double reach, Predicate<AABB> clear, Predicate<Vec3> acceptableGoal) {
        requirePosition(start);
        dimensions(width, height);
        if (target == null || !Double.isFinite(eyeHeight) || eyeHeight < 0 || eyeHeight > 4
            || !Double.isFinite(reach) || reach <= 1 || reach > MAX_DISTANCE) throw new IllegalArgumentException("Invalid printing reach or eye height.");
        Vec3 center = Vec3.atCenterOf(target);
        if (start.distanceTo(center) > MAX_DISTANCE + reach + eyeHeight) return List.of();
        // The future block must not intersect the player's body, even before it exists in the real world.
        AABB reserved = new AABB(target);
        Predicate<Vec3> goal = p -> placementReach(p, target, eyeHeight, reach) && !body(p, width, height).intersects(reserved)
            && acceptableGoal.test(p);
        return search(start, width, height, clear, goal,
            p -> Math.max(0, p.add(0, eyeHeight, 0).distanceTo(center) - reach));
    }

    /** Caller chooses a real, safe full-block floor; route keeps standing-height clearance for landing. */
    public static List<Vec3> routeToLanding(Vec3 start, BlockPos floor, double width, double standingHeight,
                                           Predicate<AABB> clear, Predicate<BlockPos> safeFloor) {
        if (floor == null) return List.of();
        Vec3 feet = Vec3.atBottomCenterOf(floor.above());
        AABB body = body(feet, width, standingHeight);
        for (int x = (int) Math.floor(body.minX); x <= (int) Math.floor(Math.nextDown(body.maxX)); x++) {
            for (int z = (int) Math.floor(body.minZ); z <= (int) Math.floor(Math.nextDown(body.maxZ)); z++) {
                if (!safeFloor.test(new BlockPos(x, floor.getY(), z))) return List.of();
            }
        }
        return route(start, feet, width, standingHeight, clear);
    }

    /** A real, collision-checked escape to wholly outside the build plus a one-block margin. Never drills. */
    public static List<Vec3> escapeRoute(Vec3 start, AABB build, double width, double height, Predicate<AABB> clear) {
        AABB player = body(start, width, height);
        if (build == null || !finite(build.minX) || !finite(build.minY) || !finite(build.minZ)
            || !finite(build.maxX) || !finite(build.maxY) || !finite(build.maxZ) || !clear.test(player)) return List.of();
        AABB boundary = build.inflate(1);
        if (!player.intersects(boundary)) return List.of(copy(start));
        List<Vec3> exits = new ArrayList<>(List.of(
            new Vec3(boundary.minX - width / 2 - .01, start.y, start.z),
            new Vec3(boundary.maxX + width / 2 + .01, start.y, start.z),
            new Vec3(start.x, boundary.minY - height - .01, start.z),
            new Vec3(start.x, boundary.maxY + .01, start.z),
            new Vec3(start.x, start.y, boundary.minZ - width / 2 - .01),
            new Vec3(start.x, start.y, boundary.maxZ + width / 2 + .01)));
        exits.sort(Comparator.comparingDouble(start::distanceToSqr));
        for (Vec3 exit : exits) {
            if (finite(exit.x) && finite(exit.y) && finite(exit.z) && segmentClear(start, exit, width, height, clear))
                return List.of(copy(start), exit);
        }
        for (Vec3 exit : exits) {
            if (!finite(exit.x) || !finite(exit.y) || !finite(exit.z) || start.distanceToSqr(exit) > MAX_DISTANCE * MAX_DISTANCE) continue;
            List<Vec3> path = route(start, exit, width, height, clear);
            if (!path.isEmpty()) return path;
        }
        return List.of();
    }

    private record Node(BlockPos cell, double cost, double score) {}

    private static List<Vec3> search(Vec3 start, double width, double height, Predicate<AABB> clear,
                                     Predicate<Vec3> goal, ToDoubleFunction<Vec3> heuristic) {
        if (!clear.test(body(start, width, height))) return List.of();
        if (goal.test(start)) return List.of(copy(start));
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score).thenComparingDouble(Node::cost)
            .thenComparingInt(n -> n.cell.getY()).thenComparingInt(n -> n.cell.getX()).thenComparingInt(n -> n.cell.getZ()));
        var costs = new HashMap<BlockPos, Double>();
        var previous = new HashMap<BlockPos, BlockPos>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos origin = BlockPos.containing(start);
        // Several safe initial centers allow starting off-grid beside a wall without snapping through it.
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            BlockPos cell = origin.offset(x, y, z);
            Vec3 point = Vec3.atBottomCenterOf(cell);
            if (!segmentClear(start, point, width, height, clear)) continue;
            double cost = start.distanceTo(point);
            costs.put(cell, cost);
            previous.put(cell, null);
            open.add(new Node(cell, cost, cost + heuristic.applyAsDouble(point)));
        }
        while (!open.isEmpty() && visited.size() < MAX_NODES) {
            Node node = open.remove();
            if (node.cost > costs.get(node.cell) || !visited.add(node.cell)) continue;
            Vec3 point = Vec3.atBottomCenterOf(node.cell);
            if (goal.test(point)) {
                List<Vec3> path = new ArrayList<>();
                for (BlockPos cell = node.cell; cell != null; cell = previous.get(cell)) path.add(Vec3.atBottomCenterOf(cell));
                path.add(copy(start));
                Collections.reverse(path);
                return List.copyOf(path);
            }
            for (int[] step : NEIGHBORS) {
                BlockPos next = node.cell.offset(step[0], step[1], step[2]);
                Vec3 nextPoint = Vec3.atBottomCenterOf(next);
                double cost = node.cost + 1;
                if (nextPoint.distanceToSqr(start) > MAX_DISTANCE * MAX_DISTANCE || visited.contains(next)
                    || costs.getOrDefault(next, Double.POSITIVE_INFINITY) <= cost
                    || !segmentClear(point, nextPoint, width, height, clear)) continue;
                costs.put(next, cost);
                previous.put(next, node.cell);
                open.add(new Node(next, cost, cost + heuristic.applyAsDouble(nextPoint)));
            }
        }
        return List.of();
    }

    private static Vec3 copy(Vec3 v) { return new Vec3(v.x, v.y, v.z); }

    private static void dimensions(double width, double height) {
        if (!Double.isFinite(width) || width <= 0 || width > 4 || !Double.isFinite(height) || height <= 0 || height > 4)
            throw new IllegalArgumentException("Invalid player flight dimensions.");
    }

    private static boolean finite(double value) { return Double.isFinite(value) && Math.abs(value) <= 30_000_000; }

    private static void requirePosition(Vec3 value) {
        if (value == null || !finite(value.x) || !finite(value.y) || !finite(value.z)) throw new IllegalArgumentException("Invalid flight position.");
    }
}
