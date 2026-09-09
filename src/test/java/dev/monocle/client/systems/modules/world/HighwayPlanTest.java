package dev.monocle.client.systems.modules.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import dev.monocle.client.systems.modules.world.HighwayPlan.Cell;
import dev.monocle.client.systems.modules.world.HighwayPlan.PavingCell;

/** Run with ./gradlew highwayBuilderCheck, or javac/java -ea without Minecraft. */
public final class HighwayPlanTest {
    public static void main(String[] args) {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) throw new IllegalStateException("Run with assertions enabled (-ea).");

        geometry();
        liquidInlets();
        supplyLayout();
        paving();
        pavingLength();
        hud();
        pavingLookback();
        passingSections();
        roadHeight();
        durability();
        routes();
        System.out.println("Highway Builder checks passed: geometry, continuous paving, reach, durability, and safe local routes.");
    }

    private static void geometry() {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            boolean diagonal = dx != 0 && dz != 0;
            for (int width = diagonal ? 3 : 1; width <= 5; width++) for (int height = 2; height <= 7; height++) {
                int floorCount = diagonal ? 2 * width - 1 : width;
                List<Cell> floor = HighwayPlan.floor(dx, dz, width);
                List<Cell> front = HighwayPlan.front(dx, dz, width, height);
                distinct(floor, floorCount);
                distinct(front, floorCount * height);
                assert floor.stream().allMatch(p -> p.y() == -1);
                for (int y = 0; y < height; y++) {
                    int level = y;
                    assert new HashSet<>(front.stream().filter(p -> p.y() == level).map(p -> p.add(0, -level - 1, 0)).toList()).equals(new HashSet<>(floor));
                }
                for (int level = -1; level <= 1; level++) {
                    List<Cell> rails = HighwayPlan.railings(dx, dz, width, height, level);
                    distinct(rails, level == 1 ? 2 * (height - 1) : 2);
                    assert rails.stream().noneMatch(front::contains) : "Railings overlap clearance";
                    assert rails.stream().noneMatch(floor::contains) : "Rail supports overlap roadway";
                    rotated(rails, HighwayPlan.railings(-dz, dx, width, height, level));
                }
                for (boolean aboveRails : new boolean[] {false, true}) {
                    List<Cell> liquids = HighwayPlan.liquids(dx, dz, width, height, aboveRails);
                    int rows = diagonal ? 2 * width + (aboveRails ? 3 : 1) : width + (aboveRails ? 4 : 2);
                    distinct(liquids, rows * (height + 1) - (diagonal ? 2 : 0));
                    assert liquids.stream().noneMatch(front::contains) : "Liquid barrier overlaps clearance";
                    rotated(liquids, HighwayPlan.liquids(-dz, dx, width, height, aboveRails));
                }
                rotated(front, HighwayPlan.front(-dz, dx, width, height));
                rotated(floor, HighwayPlan.floor(-dz, dx, width));
            }
        }

        assert new HashSet<>(HighwayPlan.floor(1, 0, 4)).equals(Set.of(new Cell(1, -1, -2), new Cell(1, -1, -1), new Cell(1, -1, 0), new Cell(1, -1, 1))) : "Even widths retain their extra left block";
        assert new HashSet<>(HighwayPlan.floor(1, 1, 4)).equals(Set.of(new Cell(2, -1, -1), new Cell(1, -1, 0), new Cell(0, -1, 1), new Cell(3, -1, -1), new Cell(2, -1, 0), new Cell(1, -1, 1), new Cell(0, -1, 2)));
        for (int width = 1; width <= 5; width++) {
            assert HighwayPlan.left(width) + HighwayPlan.right(width) + 1 == width;
            assert HighwayPlan.left(width) - HighwayPlan.right(width) == (width % 2 == 0 ? 1 : 0);
        }

        // Standing eye height is 1.62; block centers on the upper outer barrier exceed reach 7.
        for (int[] heading : new int[][] {{1, 0}, {1, 1}}) {
            List<Cell> corners = HighwayPlan.liquids(heading[0], heading[1], 5, 7, true);
            assert corners.stream().anyMatch(p -> p.x() * p.x() + p.z() * p.z() + Math.pow(p.y() + .5 - 1.62, 2) > 49) : "Five-wide, seven-high liquid barriers can still require repositioning";
            double ceilingDistance = 6.5 - 1.62;
            assert ceilingDistance > 4.5 : "Seven-high excavation needs elevation or extra mining reach, even directly below the ceiling";
        }

        for (int[] invalid : new int[][] {{0, 0, 3, 3}, {2, 0, 3, 3}, {1, 0, 0, 3}, {1, 0, 6, 3}, {1, 0, 7, 3}, {1, 0, 8, 3}, {1, 1, 6, 3}, {1, 1, 7, 3}, {1, 0, 3, 0}, {1, 0, 3, 8}, {1, 1, 1, 3}, {1, 1, 2, 3}}) {
            try {
                HighwayPlan.front(invalid[0], invalid[1], invalid[2], invalid[3]);
                throw new AssertionError("Invalid geometry accepted");
            } catch (IllegalArgumentException expected) {
                // Invalid jobs must fail before driving the player.
            }
        }
    }

    private static void distinct(List<Cell> cells, int count) {
        assert cells.size() == count : "Unexpected cell count: " + cells.size() + " != " + count;
        assert new HashSet<>(cells).size() == count : "Duplicate geometry cells";
    }

    private static void liquidInlets() {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int width = dx != 0 && dz != 0 ? 3 : 1; width <= 5; width++) for (int height = 2; height <= 7; height++) {
                for (boolean aboveRailings : new boolean[] {false, true}) {
                    Set<Cell> excavation = new HashSet<>(HighwayPlan.front(dx, dz, width, height));
                    if (aboveRailings) excavation.addAll(HighwayPlan.railings(dx, dz, width, height, 1));
                    Set<Cell> entrance = new HashSet<>();
                    for (Cell cell : excavation) entrance.add(cell.add(-dx, 0, -dz));
                    List<Cell> inlets = HighwayPlan.liquidInlets(dx, dz, width, height, aboveRailings);
                    distinct(inlets, inlets.size());
                    assert inlets.stream().noneMatch(excavation::contains) : "Liquid plugs must stay outside excavation";
                    assert inlets.stream().noneMatch(entrance::contains) : "The previous-section entrance must remain open";
                    Set<Cell> expected = new HashSet<>();
                    for (Cell cell : excavation) for (int axis = 0; axis < 3; axis++) for (int step : new int[] {-1, 1}) {
                        Cell neighbor = cell.add(axis == 0 ? step : 0, axis == 1 ? step : 0, axis == 2 ? step : 0);
                        if (!excavation.contains(neighbor) && !entrance.contains(neighbor)) expected.add(neighbor);
                    }
                    assert new HashSet<>(inlets).equals(expected) : "Seal every exposed face, and no unrelated cells";
                    rotated(inlets, HighwayPlan.liquidInlets(-dz, dx, width, height, aboveRailings));
                }
            }
        }
        List<Cell> cardinal = HighwayPlan.liquidInlets(1, 0, 3, 3, false);
        assert cardinal.containsAll(List.of(new Cell(1, -1, 0), new Cell(1, 3, 0), new Cell(1, 1, -2), new Cell(1, 1, 2), new Cell(2, 1, 0))) : "Include floor, ceiling, both sides and forward cap";
        assert !cardinal.contains(new Cell(0, 1, 0));
    }

    private static void supplyLayout() {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int width = dx != 0 && dz != 0 ? 3 : 1; width <= 5; width++) {
                Set<Cell> completedFloor = new HashSet<>();
                for (int step = -1; step >= -HighwayPlan.PAVING_LOOKBACK; step--) {
                    for (Cell floor : HighwayPlan.floor(dx, dz, width)) completedFloor.add(floor.add(dx * step, 0, dz * step));
                }
                for (boolean doubleChest : new boolean[] {false, true}) {
                    var layout = HighwayPlan.supplyLayout(dx, dz, width, doubleChest);
                    distinct(layout.positions(), doubleChest && width >= 2 ? 2 : 1);
                    assert layout.approach().equals(doubleChest && dx != 0 && dz != 0 ? new Cell(-dx, 0, -dz) : new Cell(0, 0, 0));
                    assert !layout.positions().contains(layout.approach()) : "Placing either chest must not occupy the standing approach";
                    assert completedFloor.contains(layout.approach().add(0, -1, 0));
                    Cell facing = layout.facing();
                    assert facing.y() == 0 && Math.abs(facing.x()) + Math.abs(facing.z()) == 1 : "Both chests require one shared cardinal facing";
                    assert layout.positions().getFirst().add(facing.x(), 0, facing.z()).equals(layout.approach()) : "Chest fronts face the standing approach";
                    for (Cell position : layout.positions()) {
                        assert position.y() == 0;
                        assert completedFloor.contains(position.add(0, -1, 0)) : "Even narrow and diagonal layouts must stay on completed roadway";
                        assert position.distanceSquared(layout.approach()) <= 2 : "Both placements must be reachable from the shared approach";
                    }
                    if (layout.positions().size() == 2) {
                        Cell first = layout.positions().getFirst(), second = layout.positions().getLast();
                        assert first.distanceSquared(second) == 1 : "The two chests must share a cardinal face";
                        assert second.equals(first.add(facing.z(), 0, -facing.x())) : "Place the second chest rightward when looking backward";
                        assert (second.x() - first.x()) * dz - (second.z() - first.z()) * dx > 0 : "Use the progression-left space, including the extra block of even widths";
                        Cell secondStance = layout.approach().add(second.x() - first.x(), 0, second.z() - first.z());
                        assert completedFloor.contains(secondStance.add(0, -1, 0)) : "The second stance must not require unfinished diagonal roadway";
                        assert !layout.positions().contains(secondStance) : "Neither placement stance may overlap either chest";
                        assert second.add(facing.x(), 0, facing.z()).equals(secondStance) : "Each placement stance must produce the same cardinal facing";
                        assert HighwayPlan.route(layout.approach(), secondStance,
                            p -> completedFloor.contains(p.add(0, -1, 0)) && !layout.positions().contains(p), (from, to) -> true)
                            .equals(List.of(layout.approach(), secondStance)) : "The two stances must have a direct supported walking step, with both chests present";
                    }
                    var turned = HighwayPlan.supplyLayout(-dz, dx, width, doubleChest);
                    assert layout.positions().stream().map(p -> new Cell(-p.z(), p.y(), p.x())).toList().equals(turned.positions()) : "Placement order must rotate with the highway";
                    assert new Cell(-facing.z(), 0, facing.x()).equals(turned.facing());
                    assert new Cell(-layout.approach().z(), 0, layout.approach().x()).equals(turned.approach());
                }
            }
        }
        assert HighwayPlan.supplyLayout(0, 1, 2, true).positions().equals(List.of(new Cell(0, 0, -1), new Cell(1, 0, -1))) : "Width two starts at the progression-right anchor";
        assert HighwayPlan.supplyLayout(1, 1, 3, true).positions().equals(List.of(new Cell(-1, 0, -2), new Cell(0, 0, -2))) : "Move the narrow diagonal pair one section back so both placement stances are supported";
        assert HighwayPlan.supplyLayout(1, 1, 3, true).approach().equals(new Cell(-1, 0, -1));
        assert HighwayPlan.supplyLayout(0, 1, 1, true).positions().size() == 1 : "Width one forces the option off";
        assert HighwayPlan.supplyLayout(1, 1, 5, false).positions().size() == 1 : "The option off retains single-chest restocking on wider roads";
    }

    private static void paving() {
        Cell origin = new Cell(-12, 64, 9);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int width = dx != 0 && dz != 0 ? 3 : 1; width <= 5; width++) {
                for (boolean floor : new boolean[] {false, true}) for (boolean rails : new boolean[] {false, true}) for (boolean supports : new boolean[] {false, true}) {
                    List<PavingCell> cells = HighwayPlan.paving(dx, dz, width, floor, rails, supports);
                    List<Cell> floorCells = floor ? HighwayPlan.floor(dx, dz, width) : List.of();
                    List<Cell> supportCells = rails && supports ? HighwayPlan.railings(dx, dz, width, 1, -1) : List.of();
                    List<Cell> railCells = rails ? HighwayPlan.railings(dx, dz, width, 1, 0) : List.of();
                    distinct(cells.stream().map(PavingCell::position).toList(), floorCells.size() + supportCells.size() + railCells.size());
                    assert cells.subList(0, floorCells.size()).stream().map(PavingCell::position).toList().equals(floorCells) : "Pave safe footing before edges";
                    assert cells.stream().filter(PavingCell::filler).map(PavingCell::position).toList().equals(supportCells) : "Only railing supports may use filler";
                    assert cells.subList(cells.size() - railCells.size(), cells.size()).stream().map(PavingCell::position).toList().equals(railCells) : "Place rails after their supports";
                    assert new HashSet<>(cells.stream().map(p -> new PavingCell(new Cell(-p.position().z(), p.position().y(), p.position().x()), p.filler())).toList())
                        .equals(new HashSet<>(HighwayPlan.paving(-dz, dx, width, floor, rails, supports))) : "Paving must rotate with its heading";

                    Cell nextOrigin = origin.add(dx, 0, dz);
                    Cell repairOrigin = nextOrigin.add(-dx, 0, -dz);
                    Set<Cell> previous = new HashSet<>(), current = new HashSet<>(), repair = new HashSet<>();
                    for (PavingCell planned : cells) {
                        Cell p = planned.position();
                        previous.add(origin.add(p.x(), p.y(), p.z()));
                        current.add(nextOrigin.add(p.x(), p.y(), p.z()));
                        repair.add(repairOrigin.add(p.x(), p.y(), p.z()));
                    }
                    assert repair.equals(previous) : "One-section-behind repairs retain their original world positions";
                    assert current.stream().noneMatch(previous::contains) : "Adjacent paving sections must not share targets";
                    assert nextOrigin.y() == origin.y() && !nextOrigin.equals(repairOrigin) : "Repairing behind must not move the current anchor";
                }
            }
        }
    }

    private static void passingSections() {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            double scale = Math.hypot(dx, dz);
            assert HighwayPlan.passedSection(dx, dz, 0, 0) : "The destination center completes approach";
            for (double forward : new double[] {-0.101, -0.099, 0, 0.2, 0.9, 2}) {
                for (double side : new double[] {-0.301, -0.299, 0, 0.299, 0.301}) {
                    double x = (dx * forward - dz * side) / scale;
                    double z = (dz * forward + dx * side) / scale;
                    boolean passed = HighwayPlan.passedSection(dx, dz, x, z);
                    assert passed == (forward >= -0.1 && Math.abs(side) < 0.3) : "Overshoot must advance without accepting lateral drift";
                    assert passed == HighwayPlan.passedSection(-dz, dx, -z, x) : "Approach tolerance must be direction-independent";
                }
            }
        }
        assert HighwayPlan.passedSection(1, 0, -0.1, 0) : "Longitudinal tolerance is inclusive";
        assert !HighwayPlan.passedSection(1, 0, 0, 0.3) : "Lateral tolerance is exclusive";
    }

    private static void pavingLength() {
        for (boolean diagonal : new boolean[] {false, true}) {
            double sectionLength = diagonal ? Math.sqrt(2) : 1;
            for (int completed = 0; completed <= 25; completed++) for (int retained = 0; retained <= 6; retained++) {
                for (int ahead = 1; ahead <= 2; ahead++) for (int limit : new int[] {0, 1, 2, 20}) {
                    boolean allowed = HighwayPlan.withinLength(diagonal, completed * sectionLength, retained + ahead, limit);
                    assert allowed == (limit == 0 || (completed + retained + ahead) * sectionLength <= limit + 0.001)
                        : "Preplacement and required sections share distance accounting, including unretired lookback";
                }
            }
        }
        assert HighwayPlan.withinLength(false, 13, 7, 20);
        assert !HighwayPlan.withinLength(false, 13, 8, 20) : "The final required row must not preplace beyond the test/distance limit";
        assert HighwayPlan.withinLength(true, 8 * Math.sqrt(2), 6, 20);
        assert !HighwayPlan.withinLength(true, 8 * Math.sqrt(2), 7, 20) : "Diagonal preplacement must account for sqrt(2), not round down";
    }

    private static void hud() {
        HighwayHud hud = new HighwayHud();
        assert hud.rate(10) == 0;
        hud.reset(100);
        for (int second = 1; second <= 100; second++) hud.update(100 + second, second * 4.5, 0);
        for (int window : new int[] {5, 10, 30, 60, 0}) assert Math.abs(hud.rate(window) - 4.5) < 1e-9;
        assert !hud.jammed();
        // Pause/wait time lowers real throughput; mining actions are activity, not highway distance.
        hud.update(202, 450, 500);
        assert !hud.jammed();
        hud.update(205, 450, 500);
        assert hud.jammed();
        hud.update(210, 450, 500);
        assert hud.rate(10) == 0;
        assert Math.abs(hud.rate(0) - 450.0 / 110) < 1e-9;
        hud.update(211, 440, 501);
        assert hud.distance() == 450 : "Backing up must not count toward throughput or undo already counted progress";
        hud.reset(0);
        for (int second = 1; second <= 200_000; second++) hud.update(second, second * 4.2, 0);
        assert hud.sampleCount() <= 62 : "Infinite sessions retain bounded history";
        assert Math.abs(hud.rate(0) - 4.2) < 1e-9 : "Session rate is total distance / total time, not a mean of means";
        hud.update(200_010, 840_010, 0);
        assert Math.abs(hud.rate(0) - 840_010.0 / 200_010) < 1e-9 : "Slow and fast periods must be time weighted correctly";
        hud.reset(500_000);
        assert hud.distance() == 0 && hud.rate(0) == 0 && !hud.jammed();
        hud.update(500_002.5, 10, 0);
        assert hud.rate(10) == 4 : "A fresh session uses available elapsed time, not a full unobserved window";
    }

    private static void pavingLookback() {
        assert HighwayPlan.PAVING_LOOKBACK == 2 : "Retain two repair sections behind, plus the mandatory section underfoot";
        Cell origin = new Cell(-17, 64, 23);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            for (int width : new int[] {3, 5}) {
                List<Cell> shape = HighwayPlan.paving(dx, dz, width, true, true, true).stream().map(PavingCell::position).toList();
                ArrayDeque<List<Cell>> recent = new ArrayDeque<>();
                Set<Cell> world = new HashSet<>();
                Cell lateCorrection = null;
                for (int step = 0; step < HighwayPlan.PAVING_LOOKBACK + 3; step++) {
                    Cell anchor = origin.add(dx * step, 0, dz * step);
                    List<Cell> section = shape.stream().map(p -> anchor.add(p.x(), p.y(), p.z())).toList();
                    recent.addLast(section);
                    world.addAll(section);
                    if (recent.size() > HighwayPlan.PAVING_LOOKBACK + 1) {
                        recent.removeFirst();
                    }
                    assert recent.size() == Math.min(step + 1, 3);
                    assert recent.getLast().stream().allMatch(world::contains) : "The section underfoot remains required";
                    assert recent.stream().flatMap(List::stream).distinct().count() == (long) recent.size() * shape.size() : "Lookback sections must not overlap";
                    Cell currentFeet = anchor.add(dx, 0, dz);
                    for (List<Cell> retained : recent) for (Cell p : retained) {
                        assert p.y() == origin.y() || p.y() == origin.y() - 1;
                        assert p.distanceSquared(currentFeet) <= 144 : "Even diagonal edge repairs must remain within local walking radius";
                    }
                    if (step == HighwayPlan.PAVING_LOOKBACK) {
                        lateCorrection = recent.getFirst().getFirst();
                        assert lateCorrection.equals(origin.add(shape.getFirst().x(), shape.getFirst().y(), shape.getFirst().z()));
                        world.remove(lateCorrection);
                        assert recent.stream().flatMap(List::stream).filter(p -> !world.contains(p)).toList().equals(List.of(lateCorrection)) : "A correction two sections behind must still be discoverable";
                        assert !recent.getFirst().stream().allMatch(world::contains) : "An unsuccessful background repair is allowed to age out";
                    } else if (step > HighwayPlan.PAVING_LOOKBACK) {
                        Cell repaired = lateCorrection;
                        assert recent.stream().flatMap(List::stream).noneMatch(repaired::equals) : "Do not revisit a failed repair beyond two sections";
                        assert !world.contains(repaired) : "The deliberate late-hole tradeoff must not be hidden by the test";
                    }
                }
            }
        }
    }

    private static void roadHeight() {
        for (int roadY : new int[] {-64, 0, 120, 319}) {
            double roundedDown = Math.nextDown((double) roadY);
            assert Math.floor(roundedDown) == roadY - 1 : "Raw flooring reproduces the road-as-body regression";
            for (double y : new double[] {roundedDown, roadY, Math.nextUp((double) roadY), roadY - 0.25, roadY + 0.25}) {
                assert HighwayPlan.roadFeetY(y, roadY) == roadY : "On-level footprint checks must remain anchored above the road";
            }
            assert HighwayPlan.roadFeetY(roadY - 0.26, roadY) == roadY - 1 : "Off-level positions must still require recovery";
            assert HighwayPlan.roadFeetY(roadY + 1, roadY) == roadY + 1;
        }
    }

    private static void rotated(List<Cell> cells, List<Cell> rotated) {
        assert new HashSet<>(cells.stream().map(p -> new Cell(-p.z(), p.y(), p.x())).toList()).equals(new HashSet<>(rotated)) : "Geometry changes under a quarter turn";
    }

    private static void durability() {
        assert !HighwayPlan.usableTool(1, 100, 2) : "2% must not truncate to zero";
        assert !HighwayPlan.usableTool(2, 100, 2);
        assert HighwayPlan.usableTool(3, 100, 2);
        assert !HighwayPlan.usableTool(31, 1561, 2);
        assert HighwayPlan.usableTool(32, 1561, 2);
        assert !HighwayPlan.usableTool(0, 100, 0);
        assert HighwayPlan.usableTool(1, 100, 0);
        assert !HighwayPlan.usableTool(1, 0, 2);
        assert !HighwayPlan.usableTool(100, 100, 100);
    }

    private static void routes() {
        Cell start = new Cell(0, 64, 0);
        Cell goal = start.add(4, 0, 0);
        Predicate<Cell> floor = p -> p.y() == 64;
        BiPredicate<Cell, Cell> safe = (from, to) -> true;
        assert HighwayPlan.route(start, start, floor, safe).equals(List.of(start));
        assert HighwayPlan.route(start, start, p -> false, safe).isEmpty();
        assert HighwayPlan.route(start, goal, floor, safe).size() == 5;

        Predicate<Cell> detourFloor = p -> floor.test(p) && !(p.x() == 2 && Math.abs(p.z()) <= 1);
        BiPredicate<Cell, Cell> detourEdges = (from, to) -> !(from.equals(start) && to.equals(start.add(1, 0, 0)));
        List<Cell> detour = HighwayPlan.route(start, goal, detourFloor, detourEdges);
        assert detour.size() > 5;
        validRoute(detour, start, goal, detourFloor, detourEdges);
        assert HighwayPlan.route(start, goal, floor, (from, to) -> false).isEmpty() : "Unsafe steps must not be taken";
        assert HighwayPlan.route(start, goal, p -> floor.test(p) && p.x() != 2, safe).isEmpty() : "An impassable wall must not be crossed";
        assert HighwayPlan.route(start, goal.add(0, 1, 0), floor, safe).isEmpty() : "Unsafe goal must not be entered";
        assert HighwayPlan.route(start, start.add(13, 0, 0), floor, safe).isEmpty();
        assert HighwayPlan.route(start, start.add(9, 0, 9), floor, safe).isEmpty();
        validRoute(HighwayPlan.route(start, start.add(12, 0, 0), floor, safe), start, start.add(12, 0, 0), floor, safe);

        // Both endpoints are nearby, but the only permitted detour leaves the radius-12 boundary.
        List<Cell> outsideDetour = new ArrayList<>();
        for (int x = 0; x <= 12; x++) outsideDetour.add(start.add(x, 0, 0));
        for (int x = 12; x >= 0; x--) outsideDetour.add(start.add(x, 0, 1));
        assert HighwayPlan.route(start, start.add(0, 0, 1), outsideDetour::contains, (from, to) -> outsideDetour.indexOf(to) == outsideDetour.indexOf(from) + 1).isEmpty();

        for (int direction : new int[] {-1, 1}) {
            List<Cell> stairs = List.of(start, start.add(1, direction, 0), start.add(2, 2 * direction, 0), start.add(3, 3 * direction, 0), start.add(4, 4 * direction, 0));
            List<Cell> path = HighwayPlan.route(start, stairs.get(3), stairs::contains, safe);
            assert path.equals(stairs.subList(0, 4)) : "One-block stairs within three vertical blocks must work";
            validRoute(path, start, stairs.get(3), stairs::contains, safe);
            assert HighwayPlan.route(start, stairs.get(4), stairs::contains, safe).isEmpty() : "Local routes must stay within three vertical blocks";
        }
        Set<Cell> cliff = Set.of(start, start.add(1, 2, 0));
        assert HighwayPlan.route(start, start.add(1, 2, 0), cliff::contains, safe).isEmpty() : "Cannot climb two blocks in one step";
        Set<Cell> vertical = Set.of(start, start.add(0, 1, 0));
        assert HighwayPlan.route(start, start.add(0, 1, 0), vertical::contains, safe).isEmpty() : "Cannot fly straight up";
    }

    private static void validRoute(List<Cell> path, Cell start, Cell goal, Predicate<Cell> standable, BiPredicate<Cell, Cell> safe) {
        assert !path.isEmpty();
        assert path.getFirst().equals(start) && path.getLast().equals(goal);
        assert new HashSet<>(path).size() == path.size() : "Route must not loop";
        for (int i = 0; i < path.size(); i++) {
            Cell cell = path.get(i);
            assert standable.test(cell) : "Unsafe footing";
            assert cell.distanceSquared(start) <= 144 && Math.abs(cell.y() - start.y()) <= 3;
            if (i == 0) continue;
            Cell previous = path.get(i - 1);
            assert Math.abs(cell.x() - previous.x()) + Math.abs(cell.z() - previous.z()) == 1;
            assert Math.abs(cell.y() - previous.y()) <= 1;
            assert safe.test(previous, cell) : "Unsafe transition";
        }
    }
}
