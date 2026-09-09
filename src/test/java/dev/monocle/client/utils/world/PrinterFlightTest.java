package dev.monocle.client.utils.world;

import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFlightMode;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Assertion-based native geometry and production flight-lease guards; no running client or server. */
public final class PrinterFlightTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with assertions enabled (-ea).");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        chunksAndSweeps();
        routes();
        printPositions();
        landings();
        escapes();
        steering();
        System.out.println("Printer flight checks passed: bounded swept-body routing, loaded chunks, printing reach, safe landing, verified escape and short-lived steering.");
    }

    private static void chunksAndSweeps() {
        Set<String> touched = new HashSet<>();
        assert PrinterFlight.loaded(new AABB(-16, 0, 0, 0, 1, 16), (x, z) -> touched.add(x + ":" + z));
        assert touched.equals(Set.of("-1:0")) : "Exact zero is an exclusive boundary, not chunk zero";
        touched.clear();
        assert PrinterFlight.loaded(new AABB(-.3, 0, -16.1, .3, 1, -15.9), (x, z) -> { touched.add(x + ":" + z); return true; });
        assert touched.equals(Set.of("-1:-2", "-1:-1", "0:-2", "0:-1"));
        assert !PrinterFlight.loaded(new AABB(-.3, 0, 0, .3, 1, 1), (x, z) -> x == 0) : "Whole player body must be in loaded chunks";
        assert !PrinterFlight.loaded(new AABB(Double.NaN, 0, 0, 1, 1, 1), (x, z) -> true);
        assert !PrinterFlight.loaded(new AABB(0, 0, 0, 10000, 1, 1), (x, z) -> true);

        AABB thinWall = new AABB(1.0, -1, -5, 1.01, 5, 5);
        Vec3 a = new Vec3(.5, 1, .5), b = new Vec3(1.5, 1, .5);
        assert !PrinterFlight.segmentClear(a, b, .6, .6, clear(thinWall));
        assert !PrinterFlight.segmentClear(a, a.add(0, 1, 0), .6, 1.8, clear(new AABB(0, 2.9, 0, 1, 3, 1))) : "Ceiling must check the player's head, not just feet";
        assert PrinterFlight.segmentClear(a, a, .6, .6, clear(thinWall));
        assert !PrinterFlight.segmentClear(a, a.add(129, 0, 0), .6, .6, box -> true);
        invalid(() -> PrinterFlight.body(Vec3.ZERO, 0, .6));
        invalid(() -> PrinterFlight.body(new Vec3(Double.NaN, 0, 0), .6, .6));
    }

    private static void routes() {
        Vec3 start = new Vec3(-3.5, 2, .5), end = new Vec3(3.5, 2, .5);
        assert PrinterFlight.route(start, end, .6, .6, box -> true).equals(List.of(start, end));
        AABB wall = new AABB(-.1, 0, -2, .1, 5, 2);
        List<Vec3> route = PrinterFlight.route(start, end, .6, .6, clear(wall));
        assert route.size() > 2 : "A wall requires a genuine detour";
        assert route.getFirst().equals(start) && route.getLast().equals(end);
        checkedRoute(route, .6, .6, clear(wall));
        assert route.stream().allMatch(p -> p.distanceToSqr(start) <= PrinterFlight.MAX_DISTANCE * PrinterFlight.MAX_DISTANCE);
        try { route.add(Vec3.ZERO); throw new AssertionError("Mutable route"); } catch (UnsupportedOperationException expected) {}
        assert PrinterFlight.route(start, end, .6, .6, clear(new AABB(-100, -100, -100, 100, 100, 100))).isEmpty();
        assert PrinterFlight.route(start, start.add(33, 0, 0), .6, .6, box -> true).isEmpty();
        Predicate<AABB> enclosed = box -> box.minX >= -4 && box.maxX <= 4 && box.minY >= 0 && box.maxY <= 4 && box.minZ >= -4 && box.maxZ <= 4;
        AtomicInteger checks = new AtomicInteger();
        assert PrinterFlight.route(start, end.add(2, 0, 0), .6, .6, box -> { checks.incrementAndGet(); return enclosed.test(box); }).isEmpty();
        assert checks.get() <= 2 + 27 + PrinterFlight.MAX_NODES * 7 : "Failure must remain bounded";
    }

    private static void printPositions() {
        BlockPos target = new BlockPos(0, 3, 0);
        Vec3 center = Vec3.atCenterOf(target);
        assert !PrinterFlight.placementReach(center.add(1, 0, 0), target, 0, 4.5) : "One-block exclusion is strict";
        assert !PrinterFlight.placementReach(center.add(0, -1.6, 0), target, 1.6, 4.5) : "Eye exclusion is independent from feet";
        assert PrinterFlight.placementReach(center.add(4.5, 0, 0), target, 0, 4.5);
        assert !PrinterFlight.placementReach(center.add(4.5001, 0, 0), target, 0, 4.5);
        Vec3 start = new Vec3(8.5, 3, .5);
        List<Vec3> route = PrinterFlight.routeToPlacement(start, target, .6, .6, .4, 4.5, box -> true);
        assert !route.isEmpty() && PrinterFlight.placementReach(route.getLast(), target, .4, 4.5);
        assert !PrinterFlight.body(route.getLast(), .6, .6).intersects(new AABB(target));
        checkedRoute(route, .6, .6, box -> true);
        Vec3 perch = route.getLast();
        List<Vec3> alternate = PrinterFlight.routeToPlacement(perch, target, .6, .6, .4, 4.5, box -> true,
            point -> point.distanceToSqr(perch) > .6 && point.y <= perch.y);
        assert !alternate.isEmpty() && alternate.getLast().distanceToSqr(perch) > .6 && alternate.getLast().y <= perch.y;
        checkedRoute(alternate, .6, .6, box -> true);
        assert PrinterFlight.routeToPlacement(start, new BlockPos(100, 3, 0), .6, .6, .4, 4.5, box -> true).isEmpty();
        // Caller can forbid a whole schematic interior; no direct or search shortcut may ignore that predicate.
        AABB reserved = new AABB(-6, -6, -6, 6, 10, 6);
        assert PrinterFlight.routeToPlacement(start, target, .6, .6, .4, 4.5, clear(reserved)).isEmpty();
    }

    private static void landings() {
        BlockPos floor = new BlockPos(2, 0, 2);
        Predicate<AABB> air = box -> box.minY >= 1;
        List<Vec3> path = PrinterFlight.routeToLanding(new Vec3(.5, 4, .5), floor, .6, 1.8, air, floor::equals);
        assert !path.isEmpty() && path.getLast().equals(Vec3.atBottomCenterOf(floor.above()));
        checkedRoute(path, .6, 1.8, air);
        assert PrinterFlight.routeToLanding(new Vec3(.5, 4, .5), floor, .6, 1.8, air, p -> false).isEmpty();
        assert PrinterFlight.routeToLanding(new Vec3(.5, 4, .5), floor, 1.4, 1.8, air, floor::equals).isEmpty() : "Wide footprints cannot land on one unsupported block";
        assert PrinterFlight.routeToLanding(new Vec3(.5, 4, .5), floor, .6, 1.8,
            clear(new AABB(2, 2, 2, 3, 3, 3)), floor::equals).isEmpty() : "Gliding clearance alone is not safe landing headroom";
    }

    private static void escapes() {
        AABB build = new AABB(0, 0, 0, 8, 8, 8);
        Vec3 start = new Vec3(4.5, 4, 4.5);
        List<Vec3> direct = PrinterFlight.escapeRoute(start, build, .6, .6, box -> true);
        assert direct.size() == 2 && !PrinterFlight.body(direct.getLast(), .6, .6).intersects(build.inflate(1));
        checkedRoute(direct, .6, .6, box -> true);
        Vec3 outside = new Vec3(-3, 4, 4);
        assert PrinterFlight.escapeRoute(outside, build, .6, .6, box -> true).equals(List.of(outside));

        List<AABB> shell = new ArrayList<>(List.of(new AABB(0, 0, 0, 8, 1, 8), new AABB(0, 7, 0, 8, 8, 8),
            new AABB(0, 0, 0, 1, 8, 8), new AABB(7, 0, 0, 8, 8, 8), new AABB(0, 0, 0, 8, 8, 1), new AABB(0, 0, 7, 8, 8, 8)));
        Predicate<AABB> sealed = box -> shell.stream().noneMatch(box::intersects);
        assert PrinterFlight.escapeRoute(start, build, .6, .6, sealed).isEmpty() : "A sealed shell is not an escape";
        shell.removeLast();
        shell.add(new AABB(0, 0, 7, 2, 8, 8));
        shell.add(new AABB(4, 0, 7, 8, 8, 8));
        Predicate<AABB> doorway = box -> shell.stream().noneMatch(box::intersects);
        List<Vec3> detour = PrinterFlight.escapeRoute(start, build, .6, .6, doorway);
        assert !detour.isEmpty() && detour.size() > 2 : "Escape must detour through the offset doorway";
        assert !PrinterFlight.body(detour.getLast(), .6, .6).intersects(build.inflate(1));
        checkedRoute(detour, .6, .6, doorway);
        assert PrinterFlight.escapeRoute(new Vec3(50, 50, 50), new AABB(0, 0, 0, 100, 100, 100), .6, .6, box -> true).size() == 2;
    }

    private static void steering() throws Exception {
        Vec3 from = new Vec3(.5, 2, .5), to = from.add(.1, .1, .1);
        assert PrinterFlight.safeVelocity(from, to, .5, .6, .6, box -> true).equals(to.subtract(from));
        Vec3 limited = PrinterFlight.safeVelocity(from, from.add(10, 10, 10), .25, .6, .6, box -> true);
        assert Math.abs(limited.length() - .25) < 1e-10;
        assert PrinterFlight.safeVelocity(from, to, .5, .6, .6, box -> false).equals(Vec3.ZERO);
        assert PrinterFlight.safeVelocity(from, from, .5, .6, .6, box -> true).equals(Vec3.ZERO);
        invalid(() -> PrinterFlight.safeVelocity(from, to, 1.1, .6, .6, box -> true));
        invalid(() -> PrinterFlight.safeVelocity(from, to, Double.NaN, .6, .6, box -> true));
        assert ElytraFly.freshAutopilotRequest(10, 10) && ElytraFly.freshAutopilotRequest(10, 11);
        assert !ElytraFly.freshAutopilotRequest(10, 12) && !ElytraFly.freshAutopilotRequest(10, 9);
        assert ElytraFly.freshAutopilotRequest(Integer.MAX_VALUE, Integer.MIN_VALUE) : "Tick rollover must not make an immortal request";
        assert !ElytraFly.freshAutopilotRequest(Integer.MAX_VALUE, Integer.MIN_VALUE + 1);
        assert invokes(ElytraFlightMode.class, "onTick", "hasAutopilotRequest") : "Restocking lease must suppress inventory interference";
        assert invokes(ElytraFly.class, "onPlayerMove", "hasAutopilotRequest");
        assert invokes(ElytraFly.class, "onPlayerMove", "segmentClear") : "Check actual movement immediately, not just the cached route";
        assert invokes(Class.forName(ElytraFly.class.getName() + "$StaticGroundListener"), "chestSwapGroundListener", "hasAutopilotRequest");
        assert invokes(Class.forName(ElytraFly.class.getName() + "$StaticInstaDropListener"), "onInstadropTick", "hasAutopilotRequest");
        for (String method : List.of("onDeactivate", "onModeChanged", "onPacketReceive")) assert invokes(ElytraFly.class, method, "clearAutopilot");
        for (String method : List.of("requestAutopilot", "clearAutopilot")) {
            for (String forbidden : List.of("toggle", "setYRot", "setXRot", "setDown", "send")) assert !invokes(ElytraFly.class, method, forbidden);
        }
    }

    private static boolean invokes(Class<?> type, String method, String call) throws Exception {
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            var parsed = ClassFile.of().parse(stream.readAllBytes());
            return parsed.methods().stream().filter(m -> m.methodName().stringValue().equals(method))
                .flatMap(m -> m.code().stream()).flatMap(c -> c.elementList().stream())
                .filter(e -> e instanceof InvokeInstruction).map(e -> (InvokeInstruction) e)
                .anyMatch(i -> i.name().equalsString(call));
        }
    }

    private static Predicate<AABB> clear(AABB obstacle) { return box -> !box.intersects(obstacle); }

    private static void checkedRoute(List<Vec3> path, double width, double height, Predicate<AABB> clear) {
        for (Vec3 point : path) assert clear.test(PrinterFlight.body(point, width, height));
        for (int i = 1; i < path.size(); i++) assert PrinterFlight.segmentClear(path.get(i - 1), path.get(i), width, height, clear);
    }

    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid flight input accepted"); }
        catch (IllegalArgumentException expected) {}
    }
}
