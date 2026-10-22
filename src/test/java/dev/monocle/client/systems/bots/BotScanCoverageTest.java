package dev.monocle.client.systems.bots;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.*;

/** Geometry is tested independently of Minecraft world/pathfinding instances. */
final class BotScanCoverageTest {
    static void run(){
        List<BlockPos> grid=new ArrayList<>();
        for(int x=0;x<=4;x+=2)for(int z=0;z<=4;z+=2)grid.add(new BlockPos(x,64,z));
        BlockPos anchor=grid.getFirst();Vec3 from=new Vec3(2.5,64,2.5);
        Function<BlockPos,Vec3> floor=p->p.getY()==63?Vec3.atBottomCenterOf(p.above()):null;
        var stop=BotScanCoverage.choose(anchor,grid,from,1.62,.4,floor,p->true,p->false,(eye,p)->true,false);
        assert stop!=null&&!stop.flying()&&stop.containers().size()==9&&stop.feet().equals(from)
            : "A central safe stance covers the grid rather than walking to each chest";
        assert stop.covers(anchor)&&!stop.covers(null);
        assert stop.containers().stream().allMatch(p->BotScanCoverage.reachable(stop.eye(),p));
        BlockPos blocked=grid.getLast();
        var occluded=BotScanCoverage.choose(anchor,grid,from,1.62,.4,floor,p->true,p->false,(eye,p)->!p.equals(blocked),false);
        assert occluded!=null&&occluded.containers().size()==8&&!occluded.covers(blocked) : "Distance alone cannot certify coverage";
        assert BotScanCoverage.choose(anchor,grid,from,1.62,.4,floor,p->false,p->false,(eye,p)->true,false)==null
            : "No supported safe stance must fall back to normal navigation";
        assert BotScanCoverage.choose(anchor,grid,from,1.62,.4,floor,p->true,p->false,(eye,p)->!p.equals(anchor),false)==null
            : "Every stop must expose the anchor, not just unrelated nearby chests";
        List<BlockPos> vertical=List.of(new BlockPos(0,64,0),new BlockPos(0,66,0),new BlockPos(0,68,0));
        var hover=BotScanCoverage.choose(vertical.getFirst(),vertical,new Vec3(3.5,66,.5),1.62,.4,p->null,p->false,p->true,(eye,p)->true,true);
        assert hover!=null&&hover.flying()&&hover.containers().size()==3;
        assert BotScanCoverage.choose(anchor,grid,from,1.62,.4,p->null,p->false,p->true,(eye,p)->true,false)==null
            : "The optimizer cannot take off merely because an airborne stance scores well";
        var halfFloor=BotScanCoverage.choose(anchor,grid,from,1.62,.4,p->p.getY()==63?new Vec3(p.getX()+.5,63.875,p.getZ()+.5):null,p->true,p->false,(eye,p)->true,false);
        assert halfFloor!=null&&halfFloor.feet().y==63.875 : "Actual chest collision heights are preserved";
        assert BotScanCoverage.walkingCell(new Vec3(.5,63.875,.5)).equals(new BlockPos(0,64,0))
            &&BotScanCoverage.walkingCell(new Vec3(.5,64,.5)).equals(new BlockPos(0,64,0)) : "Baritone rounds chest-top feet upward, unlike raw blockPosition";
        int[] visibility={0},safety={0},floorCalls={0};List<BlockPos> many=new ArrayList<>();
        for(int i=0;i<200;i++)many.add(new BlockPos(i%7,64,i/7%7));
        BotScanCoverage.choose(anchor,many,from,1.62,.4,p->{floorCalls[0]++;return floor.apply(p);},p->{safety[0]++;return true;},p->false,(eye,p)->{visibility[0]++;return true;},false);
        assert floorCalls[0]==343&&safety[0]<=BotScanCoverage.FINALISTS&&visibility[0]<=BotScanCoverage.VISIBLE_STOPS*(BotScanCoverage.TARGETS+1)
            : "World safety and visibility work stays bounded even for a large candidate queue";
        assert BotScanCoverage.reachable(new Vec3(.5,64.5,.5),new BlockPos(4,64,0));
        assert !BotScanCoverage.reachable(new Vec3(.5,64.5,.5),new BlockPos(5,64,0));
        assert BotScanCoverage.score(8,2)>BotScanCoverage.score(4,0) : "A small detour can serve a larger group";
        assert BotScanCoverage.score(8,0)>BotScanCoverage.score(8,2) : "Equal coverage favors less travel";
        for(boolean rotate:List.of(false,true)){
            List<BlockPos> diagonal=new ArrayList<>();for(int i=0;i<6;i++)diagonal.add(new BlockPos(rotate?i:2*i,64+i%2,rotate?2*i:i));
            var d=BotScanCoverage.choose(diagonal.getFirst(),diagonal,new Vec3(1.5,64,1.5),1.62,.4,floor,p->true,p->false,(eye,p)->true,false);
            assert d!=null&&d.covers(diagonal.getFirst())&&d.containers().size()>1 : "Both orientations share nearby diagonal rows";
        }
        System.out.println("Scan coverage checks passed: grouped reach, occlusion, supported/hover stances, layout orientation and bounded search.");
    }
}
