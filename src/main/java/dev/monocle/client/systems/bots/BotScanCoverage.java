package dev.monocle.client.systems.bots;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.*;

/** Bounded local coverage, not a second pathfinder. All accepted stops still use checked navigation. */
final class BotScanCoverage {
    static final double REACH=4.2;
    static final int TARGETS=64,FINALISTS=48,VISIBLE_STOPS=8;
    record Stop(Vec3 feet,boolean flying,double eyeHeight,Set<BlockPos> containers){
        Stop{containers=Set.copyOf(containers);}
        Vec3 eye(){return feet.add(0,eyeHeight,0);}
        boolean covers(BlockPos target){return target!=null&&containers.contains(target);}
    }
    private record Candidate(Vec3 feet,boolean flying,double eyeHeight,int count,double cost){}
    static boolean reachable(Vec3 eye,BlockPos chest){return eye.distanceToSqr(Vec3.atCenterOf(chest))<=REACH*REACH;}
    static BlockPos walkingCell(Vec3 feet){return BlockPos.containing(feet.add(0,.1251,0));}
    static double score(int count,double distance){return count/(1+distance*.2);}
    static Stop choose(BlockPos anchor,List<BlockPos> queued,Vec3 from,double walkingEye,double flyingEye,
                       Function<BlockPos,Vec3> floor,Predicate<Vec3> walkingSafe,Predicate<Vec3> flyingSafe,
                       BiPredicate<Vec3,BlockPos> visible,boolean allowFlying){
        List<BlockPos> targets=new ArrayList<>();targets.add(anchor);
        queued.stream().filter(p->!p.equals(anchor)&&p.distSqr(anchor)<=100).distinct().limit(TARGETS-1).forEach(targets::add);
        List<Candidate> options=new ArrayList<>();Set<String> seen=new HashSet<>();
        // Centers align with safe walking cells; sample heights include chest/hopper collision surfaces.
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=-3;y<=3;y++){
            BlockPos block=anchor.offset(x,y,z);Vec3 feet=floor.apply(block);
            if(feet!=null)add(options,seen,anchor,targets,from,feet,false,walkingEye);
            if(allowFlying)add(options,seen,anchor,targets,from,Vec3.atCenterOf(block),true,flyingEye);
        }
        options.sort(Comparator.<Candidate>comparingDouble(c->-score(c.count,c.cost)).thenComparingDouble(Candidate::cost)
            .thenComparing(c->c.flying).thenComparingDouble(c->c.feet.x).thenComparingDouble(c->c.feet.y).thenComparingDouble(c->c.feet.z));
        Stop best=null;double bestScore=-1;int valid=0;
        for(int i=0;i<Math.min(FINALISTS,options.size())&&valid<VISIBLE_STOPS;i++){
            Candidate c=options.get(i);if(!(c.flying?flyingSafe:walkingSafe).test(c.feet))continue;
            Vec3 eye=c.feet.add(0,c.eyeHeight,0);if(!visible.test(eye,anchor))continue;
            valid++;Set<BlockPos> covered=new LinkedHashSet<>();
            for(BlockPos target:targets)if(reachable(eye,target)&&visible.test(eye,target))covered.add(target);
            double score=score(covered.size(),c.cost);
            if(score>bestScore){best=new Stop(c.feet,c.flying,c.eyeHeight,covered);bestScore=score;}
        }
        return best;
    }
    private static void add(List<Candidate> options,Set<String> seen,BlockPos anchor,List<BlockPos> targets,Vec3 from,Vec3 feet,boolean flying,double eyeHeight){
        if(!Double.isFinite(feet.x)||!Double.isFinite(feet.y)||!Double.isFinite(feet.z))return;
        Vec3 eye=feet.add(0,eyeHeight,0);if(!reachable(eye,anchor)||!seen.add(feet.toString()+":"+flying))return;
        int count=0;for(BlockPos target:targets)if(reachable(eye,target))count++;
        options.add(new Candidate(feet,flying,eyeHeight,count,from.distanceTo(feet)+(flying?2:0)));
    }
}
