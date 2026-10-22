package dev.monocle.client.utils.world;

import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFlightMode;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Function;

/** Assertion-based native geometry and production flight-lease guards; no running client or server. */
public final class PrinterFlightTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with assertions enabled (-ea).");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        chunksAndSweeps();
        cruiseClearance();
        routes();
        localRoutes();
        printPositions();
        interactions();
        landings();
        partialLandings();
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
        Vec3 diagonal = new Vec3(6.5, 1, 6.5);
        assert PrinterFlight.segmentClear(a, diagonal, .6, .6, clear(new AABB(1, 0, 4, 2, 3, 5)))
            : "A block beside a diagonal corridor must not block the whole flight rectangle";
        assert !PrinterFlight.segmentClear(a, diagonal, .6, .6, clear(new AABB(3.001, 0, 3, 3.002, 3, 4)))
            : "Every interval is swept; thin walls between endpoints cannot be skipped";
        AtomicInteger sweeps = new AtomicInteger();
        assert PrinterFlight.segmentClear(a, a.add(80, 0, 80), .6, .6, box -> {
            sweeps.incrementAndGet();
            assert box.maxX - box.minX <= 1.600001 && box.maxZ - box.minZ <= 1.600001;
            return true;
        });
        assert sweeps.get() == 80 : "Long diagonal collision checks grow with distance, not the enclosing area";
        assert !PrinterFlight.segmentClear(a, a.add(129, 0, 0), .6, .6, box -> true);
        invalid(() -> PrinterFlight.body(Vec3.ZERO, 0, .6));
        invalid(() -> PrinterFlight.body(new Vec3(Double.NaN, 0, 0), .6, .6));
    }

    private static void cruiseClearance(){
        AABB floor=new AABB(-100,0,-100,100,1,100);
        Predicate<AABB> clear=b->!b.intersects(floor);
        Predicate<AABB> cruise=b->clear.test(PrinterFlight.cruiseEnvelope(b));
        assert !cruise.test(PrinterFlight.body(new Vec3(.5,3.99,.5),.6,.6));
        Vec3 high=new Vec3(.5,4.05,.5);
        assert cruise.test(PrinterFlight.body(high,.6,.6)) : "Clearance is measured from the body to the floor, not the block center";
        AABB wall=new AABB(3.69,0,-5,4,10,5),ceiling=new AABB(-5,7.64,-5,5,8,5);
        assert PrinterFlight.cruiseEnvelope(PrinterFlight.body(high,.6,.6)).intersects(wall);
        assert PrinterFlight.cruiseEnvelope(PrinterFlight.body(high,.6,.6)).intersects(ceiling) : "Sidewalls and ceilings have the same three-block margin";
        Vec3 grounded=new Vec3(.5,1,.5);
        Vec3 exit=PrinterFlight.clearanceExit(grounded,.6,.6,clear,cruise);
        assert exit!=null&&exit.y>=4&&PrinterFlight.segmentClear(grounded,exit,.6,.6,clear)&&cruise.test(PrinterFlight.body(exit,.6,.6));
        assert PrinterFlight.clearanceExit(high,.6,.6,clear,cruise).equals(high);
        Predicate<AABB> bankClear=b->b.minY>=0&&!b.intersects(new AABB(3,0,-100,80,12,100));
        Predicate<AABB> bankCruise=b->bankClear.test(PrinterFlight.cruiseEnvelope(b));
        Vec3 edge=new Vec3(-.324,5,.5);
        assert bankCruise.test(PrinterFlight.body(edge,.6,.6))&&!bankCruise.test(PrinterFlight.body(edge,.72,.6))
            : "A real-body clearance check can pass while padded flight routing rejects the same position";
        Vec3 bankExit=PrinterFlight.clearanceExit(edge,.72,.6,bankClear,bankCruise);
        assert bankExit!=null&&bankCruise.test(PrinterFlight.body(bankExit,.72,.6));
        Vec3 bankGoal=new Vec3(55,8,.5);
        var bankRoute=new PrinterFlight.LocalRoute();
        Vec3 detour=bankRoute.next(bankExit,bankGoal,0,.72,.6,bankCruise);
        assert detour!=null&&!PrinterFlight.safeVelocity(bankExit,detour,.3,.72,.6,bankCruise).equals(Vec3.ZERO)
            : "Long storage banks need a checked detour when both 8- and 24-block projections are occupied";
        Vec3 bankPosition=bankExit;
        for(int tick=1;tick<400&&bankPosition.distanceTo(bankGoal)>32;tick++){
            Vec3 next=bankRoute.next(bankPosition,bankGoal,tick,.72,.6,bankCruise);
            assert next!=null : "The detour must keep moving until final-approach range";
            Vec3 velocity=PrinterFlight.safeVelocity(bankPosition,next,.3,.72,.6,bankCruise);
            assert velocity.lengthSqr()>0&&bankCruise.test(PrinterFlight.body(bankPosition.add(velocity),.72,.6));
            bankPosition=bankPosition.add(velocity);
        }
        assert bankPosition.distanceTo(bankGoal)<=32 : "Reach bounded final approach without giving up cruise clearance";
        Predicate<AABB> tunnel=b->clear.test(b)&&b.maxY<=3;
        assert PrinterFlight.clearanceExit(grounded,.6,.6,tunnel,b->tunnel.test(PrinterFlight.cruiseEnvelope(b)))==null
            : "A narrow tunnel cannot waive cruise clearance";
        BlockPos chest=new BlockPos(0,1,0);AABB chestBlock=new AABB(chest);
        Predicate<AABB> chestClear=b->clear.test(b)&&!b.intersects(chestBlock);
        Predicate<AABB> chestCruise=b->chestClear.test(PrinterFlight.cruiseEnvelope(b));
        Vec3 start=new Vec3(8,5.05,.5);
        var approach=PrinterFlight.routeToInteraction(start,chest,.6,.6,.4,4.4,chestCruise,p->true);
        assert !approach.isEmpty() : "Containers remain within opening reach without skimming their tops";
        checkedRoute(approach,.6,.6,chestCruise);
        assert approach.getLast().add(0,.4,0).distanceTo(Vec3.atCenterOf(chest))<=4.4;
        assert PrinterFlight.safeVelocity(high,high.add(0,-1,0),.3,.6,.6,cruise).equals(Vec3.ZERO)
            : "Cruise descent cannot cross the ground buffer even when the next body would not collide";
        assert !PrinterFlight.safeVelocity(high,high.add(0,-1,0),.3,.6,.6,clear).equals(Vec3.ZERO)
            : "Explicit landing retains its collision-checked descent exception";
        BlockPos topChest=new BlockPos(0,255,0);
        Predicate<AABB> topClear=clear(new AABB(topChest));
        Predicate<AABB> topCruise=b->topClear.test(PrinterFlight.cruiseEnvelope(b));
        Vec3 topExit=PrinterFlight.clearanceExit(new Vec3(.5,256,.5),.6,.6,topClear,topCruise);
        assert topExit!=null&&topExit.y>=259&&topCruise.test(PrinterFlight.body(topExit,.6,.6))
            : "A top-row chest needs a valid cruise altitude above build height";
        assert PrinterFlight.body(topExit,.6,.6).maxY>257 : "The old autopilot build-height ceiling rejects this planned route";
        assert !PrinterFlight.safeVelocity(topExit,topExit.add(0,1,0),.3,.6,.6,topCruise).equals(Vec3.ZERO);
        Predicate<AABB> dense=b->b.minY>=1&&b.maxY<=5&&!b.intersects(chestBlock);
        assert PrinterFlight.routeToInteraction(new Vec3(8,2,.5),chest,.6,.6,.4,4.4,
            b->dense.test(PrinterFlight.cruiseEnvelope(b)),p->true).isEmpty();
        var close=PrinterFlight.routeToInteraction(new Vec3(8,2,.5),chest,.6,.6,.4,4.4,dense,p->true);
        assert !close.isEmpty() : "Dense storage still permits a real-body checked final approach";
        checkedRoute(close,.6,.6,dense);
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
        assert route.size() == 2 : "An unobstructed placement flight should not follow one-block grid turns";
        assert !PrinterFlight.body(route.getLast(), .6, .6).intersects(new AABB(target));
        checkedRoute(route, .6, .6, box -> true);
        Vec3 perch = route.getLast();
        List<Vec3> alternate = PrinterFlight.routeToPlacement(perch, target, .6, .6, .4, 4.5, box -> true,
            point -> point.distanceToSqr(perch) > .6 && point.y <= perch.y);
        assert !alternate.isEmpty() && alternate.getLast().distanceToSqr(perch) > .6 && alternate.getLast().y <= perch.y;
        checkedRoute(alternate, .6, .6, box -> true);
        BlockPos highChest = new BlockPos(0, 255, 0);
        Vec3 distantStart = new Vec3(9.5, 238, -21.5);
        assert !PrinterFlight.routeToPlacement(distantStart, highChest, .72, .7, .4, 4.4, box -> true).isEmpty()
            : "A reachable elevated chest must not exhaust the local flight search";
        assert PrinterFlight.routeToPlacement(start, new BlockPos(100, 3, 0), .6, .6, .4, 4.5, box -> true).isEmpty();
        // Caller can forbid a whole schematic interior; no direct or search shortcut may ignore that predicate.
        AABB reserved = new AABB(-6, -6, -6, 6, 10, 6);
        assert PrinterFlight.routeToPlacement(start, target, .6, .6, .4, 4.5, clear(reserved)).isEmpty();
    }

    private static void localRoutes() {
        Vec3 start = new Vec3(-3.5, 2, .5), goal = new Vec3(3.5, 2, .5);
        Predicate<AABB> world = clear(new AABB(-.1, 0, -2, .1, 5, 2));
        AtomicInteger checks = new AtomicInteger();
        Predicate<AABB> observed = box -> { checks.incrementAndGet(); return world.test(box); };
        var navigation = new PrinterFlight.LocalRoute();
        Vec3 first = navigation.next(start, goal, 0, .6, .6, observed);
        assert first != null;
        int planned = checks.get();
        assert navigation.next(start, goal, 1, .6, .6, observed).equals(first);
        assert checks.get() - planned <= 16 : "Following a cached detour must not run A* again every tick";
        Vec3 position = start;
        for (int tick = 2; tick < 300 && position.distanceToSqr(goal) >= .04; tick++) {
            Vec3 step = navigation.next(position, goal, tick, .6, .6, world);
            assert step != null : "A healthy detour must move every tick, not only on search ticks";
            Vec3 velocity = PrinterFlight.safeVelocity(position, step, .25, .6, .6, world);
            assert velocity.lengthSqr() > 0;
            position = position.add(velocity);
        }
        assert position.distanceToSqr(goal) < .04 : "The cached route must reach the destination around the wall";
        navigation.reset();
        assert navigation.next(start, goal, 0, .6, .6, world) != null;
        assert navigation.next(start, goal, 1, .6, .6, box -> false) == null : "A changed world invalidates cached steering immediately";
        assert navigation.next(start, goal, 2, .6, .6, box -> true).equals(goal) : "A cleared corridor resumes immediately during the search cooldown";
        navigation.reset();
        checks.set(0);
        Predicate<AABB> sealed = box -> { checks.incrementAndGet(); return false; };
        for (int tick = 0; tick < 20; tick++) assert navigation.next(start, goal, tick, .6, .6, sealed) == null;
        assert checks.get() == 21 : "Failed A* searches retry once per second while live corridors are still checked";
        assert navigation.next(start, goal, 20, .6, .6, sealed) == null && checks.get() == 23;
        navigation.reset();
        assert navigation.next(start, goal, 0, .6, .6, sealed) == null && checks.get() == 25 : "Reconnecting resets the old world's retry clock";
        navigation.reset();
        Vec3 distantStart = new Vec3(.5, 2, .5), distantGoal = new Vec3(20.5, 2, .5);
        Predicate<AABB> waypointWall = clear(new AABB(8, 0, -2, 10, 5, 2));
        assert !waypointWall.test(PrinterFlight.body(distantStart.add(8, 0, 0), .6, .6));
        Vec3 detour = navigation.next(distantStart, distantGoal, 0, .6, .6, waypointWall);
        assert detour != null && PrinterFlight.segmentClear(distantStart, detour, .6, .6, waypointWall)
            : "An intermediate waypoint inside a wall must not prevent a safe detour beyond it";
    }

    private static void interactions(){
        BlockPos chest=new BlockPos(0,3,0);
        // A real chest ends at 3.875. Printing reserves the air above that shape and requires >1-block separation.
        Predicate<AABB> pocket=b->b.minX>=0&&b.maxX<=3&&b.minZ>=0&&b.maxZ<=1&&b.minY>=3.875&&b.maxY<=4.9;
        Vec3 start=new Vec3(2.5,3.875,.5);
        Predicate<Vec3> exposedTop=p->Math.abs(p.x-.5)<.1&&p.y>=3.875&&p.y<=4.3;
        assert PrinterFlight.routeToPlacement(start,chest,.6,.6,.4,4.4,pocket,exposedTop).isEmpty();
        var route=PrinterFlight.routeToInteraction(start,chest,.6,.6,.4,4.4,pocket,exposedTop);
        assert !route.isEmpty()&&exposedTop.test(route.getLast()) : "Opening a partial-height chest must not inherit full-cube placement restrictions";
        checkedRoute(route,.6,.6,pocket);
        assert PrinterFlight.routeToInteraction(start,chest,.6,.6,.4,4.4,pocket,p->false).isEmpty() : "Interactions still require an exposed face";
        assert PrinterFlight.routeToInteraction(start,chest,.6,.6,.4,4.4,b->false,p->true).isEmpty() : "Never escape by ignoring actual collisions";
        Vec3 above=new Vec3(.5,3.875,.5);
        assert PrinterFlight.routeToInteraction(above,chest,.6,.6,.4,4.4,pocket,exposedTop).equals(List.of(above));
        var recovery=PrinterFlight.escapeRoute(start,new AABB(2.25,3.625,.25,2.75,4.125,.75),.6,.6,pocket);
        assert !recovery.isEmpty();checkedRoute(recovery,.6,.6,pocket);
        invalid(()->PrinterFlight.routeToInteraction(start,chest,.6,.6,.4,Double.NaN,pocket,exposedTop));
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
        assert PrinterFlight.supported(new Vec3(2.5, 1, 2.5), .6, 1.8, floor::equals);
        assert !PrinterFlight.supported(new Vec3(2.9, 1, 2.5), .6, 1.8, floor::equals)
            : "The centre's floor is insufficient when part of the player would land over a hole";
        assert PrinterFlight.supported(new Vec3(2.9, 1, 2.5), .6, 1.8, p -> p.equals(floor) || p.equals(floor.east()));
        assert !PrinterFlight.supported(new Vec3(2.9, 1, 2.9), .6, 1.8, p -> p.equals(floor) || p.equals(floor.east()) || p.equals(floor.south()))
            : "A diagonal footprint must also verify the corner block";
        assert PrinterFlight.supported(new Vec3(-.5, 1, -.5), .6, 1.8, new BlockPos(-1, 0, -1)::equals);
        assert !PrinterFlight.supported(new Vec3(-.1, 1, -.5), .6, 1.8, new BlockPos(-1, 0, -1)::equals);
    }

    private static void partialLandings() {
        BlockPos floor=BlockPos.ZERO;
        VoxelShape chest=Blocks.CHEST.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE,floor);
        AABB chestBox=chest.bounds();
        Function<BlockPos,VoxelShape> shapes=p->p.equals(floor)?chest:Shapes.empty();
        Vec3 from=new Vec3(.5,2,.5);
        Vec3 landing=PrinterFlight.landingBelow(from,.6,.6,1.8,4,shapes,clear(chestBox));
        assert landing!=null&&landing.equals(new Vec3(.5,.875,.5)) : "Land on the actual chest lid, not integer Y above it";
        assert PrinterFlight.landingBelow(new Vec3(.9,2,.5),.6,.6,1.8,4,shapes,clear(chestBox))==null
            : "A chest edge or unsupported neighboring cell cannot hold the whole footprint";
        AABB roof=new AABB(-1,3.7,-1,2,4.7,2);
        Predicate<AABB> roofed=b->!b.intersects(chestBox)&&!b.intersects(roof);
        from=new Vec3(.5,3,.5);landing=PrinterFlight.landingBelow(from,.6,.6,1.8,4,shapes,roofed);
        assert landing!=null&&!PrinterFlight.segmentClear(from,landing,.6,1.8,roofed)
            : "Gliding transit can leave a low roof; only the destination requires standing height";
        assert PrinterFlight.landingBelow(new Vec3(.5,1.2,.5),.6,.6,1.8,4,shapes,
            b->!b.intersects(chestBox)&&!b.intersects(new AABB(-1,2,-1,2,3,2)))==null
            : "A reachable chest without standing headroom remains unsafe";
        VoxelShape slab=Shapes.box(0,0,0,1,.5,1);
        assert PrinterFlight.landingBelow(new Vec3(.5,2,.5),.6,.6,1.8,4,p->p.equals(floor)?slab:Shapes.empty(),clear(slab.bounds())).y==.5;
        VoxelShape hopper=Blocks.HOPPER.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE,floor);
        Predicate<AABB> hopperClear=b->hopper.toAabbs().stream().noneMatch(b::intersects);
        landing=PrinterFlight.landingBelow(new Vec3(.5,2,.5),.6,.6,1.8,4,p->p.equals(floor)?hopper:Shapes.empty(),hopperClear);
        assert landing!=null&&landing.y>0&&landing.y<=1&&hopperClear.test(PrinterFlight.body(landing,.6,1.8))
            : "Hopper landing uses its real collision support, not an invented full top";
        VoxelShape left=Shapes.box(.0625,0,.0625,1,.875,.9375),right=Shapes.box(0,0,.0625,.9375,.875,.9375);
        Function<BlockPos,VoxelShape> pair=p->p.equals(floor)?left:p.equals(floor.east())?right:Shapes.empty();
        Predicate<AABB> pairClear=b->!b.intersects(left.bounds())&&!b.intersects(right.bounds().move(1,0,0));
        assert PrinterFlight.landingBelow(new Vec3(1,2,.5),.6,.6,1.8,4,pair,pairClear)!=null
            : "Connected chest halves support a footprint across their seam";
        assert PrinterFlight.landingBelow(new Vec3(1,2,.5),.6,.6,1.8,4,
            p->p.equals(floor)||p.equals(floor.east())?chest:Shapes.empty(),b->true)==null
            : "Separate chest shapes with a gap are not full-footprint support";
        BlockPos distant=new BlockPos(28_000_000,254,-28_000_000); // Synthetic coordinates for world-edge precision.
        Vec3 high=Vec3.atBottomCenterOf(distant).add(0,1.04,0);
        landing=PrinterFlight.landingBelow(high,.6,.6,1.8,32,p->p.equals(distant)?chest:Shapes.empty(),clear(chestBox.move(distant)));
        assert landing!=null&&landing.y==254.875 : "Fractional support works at synthetic world-edge coordinates";
        AtomicInteger queried=new AtomicInteger();
        assert PrinterFlight.landingBelow(new Vec3(.5,40,.5),.6,.6,1.8,32,p->{queried.incrementAndGet();return Shapes.empty();},b->true)==null;
        assert queried.get()==33 : "Missing or unsafe support stays missing; the downward search is bounded";
        invalid(()->PrinterFlight.landingBelow(Vec3.ZERO,.6,.6,1.8,33,shapes,b->true));
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
        Vec3 fastTarget=from.add(10,0,0);
        assert PrinterFlight.safeVelocity(from,fastTarget,2.5,.6,.6,box->true).equals(new Vec3(2.5,0,0));
        assert PrinterFlight.safeVelocity(from,fastTarget,2.5,.6,.6,box->box.maxX<from.x+2).equals(Vec3.ZERO)
            : "Fast cruise must sweep the entire tick, not jump through an intervening obstacle";
        assert PrinterFlight.safeVelocity(from,to,2.5,.6,.6,box->true).equals(to.subtract(from))
            : "Fast cruise cannot overshoot a nearby waypoint";
        invalid(() -> PrinterFlight.safeVelocity(from, to, 2.5001, .6, .6, box -> true));
        invalid(() -> PrinterFlight.safeVelocity(from, to, Double.NaN, .6, .6, box -> true));
        assert ElytraFly.freshAutopilotRequest(10, 10) && ElytraFly.freshAutopilotRequest(10, 11);
        assert !ElytraFly.freshAutopilotRequest(10, 12) && !ElytraFly.freshAutopilotRequest(10, 9);
        assert ElytraFly.freshAutopilotRequest(Integer.MAX_VALUE, Integer.MIN_VALUE) : "Tick rollover must not make an immortal request";
        assert !ElytraFly.freshAutopilotRequest(Integer.MAX_VALUE, Integer.MIN_VALUE + 1);
        assert invokes(ElytraFlightMode.class, "onTick", "hasAutopilotRequest") : "Restocking lease must suppress inventory interference";
        assert invokes(ElytraFly.class, "onPlayerMove", "hasAutopilotRequest");
        assert invokes(ElytraFly.class, "onPlayerMove", "segmentClear") : "Check actual movement immediately, not just the cached route";
        assert !invokes(ElytraFly.class,"clearAutopilotBody","getMaxY")
            &&invokes(ElytraFly.class,"clearAutopilotBody","getMinY")
            &&invokes(ElytraFly.class,"clearAutopilotBody","loaded")
            &&invokes(ElytraFly.class,"clearAutopilotBody","noCollision")
            : "Autopilot can fly above placement height while retaining void, loaded-world and collision guards";
        assert invokes(ElytraFly.class, "onTick", "hasAutopilotRequest");
        assert invokes(Class.forName("dev.monocle.client.systems.modules.player.ChestSwap$SwapListener"), "onTick", "hasAutopilotRequest")
            : "Shared landing swap must still yield to Printer steering";
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
