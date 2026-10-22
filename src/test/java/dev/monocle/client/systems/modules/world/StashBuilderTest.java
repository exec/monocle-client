package dev.monocle.client.systems.modules.world;

import dev.monocle.client.utils.world.StashLayout;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;

public final class StashBuilderTest {
    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        for(Direction facing:Direction.Plane.HORIZONTAL)for(int columns:new int[]{1,2,8,128})for(int rows:new int[]{1,2,8,19}){
            var origin=new BlockPos(100,64,-200);var plan=StashLayout.generate(origin,facing,columns,rows);
            assert plan.size()==2*columns*(2*rows-1);
            Map<BlockPos,StashLayout.Placement> byPos=new HashMap<>();
            int chests=0,hoppers=0;
            for(var p:plan){
                assert byPos.put(p.pos(),p)==null : "Never stack two construction targets in one position";
                assert p.facing()==facing;
                assert StashBuilder.matches(p,p.state(),false);
                assert !StashBuilder.matches(p,Blocks.AIR.defaultBlockState(),false);
                int row=p.pos().getY()-origin.getY();assert row>=0&&row<rows;
                if(p.kind()==StashLayout.Kind.Hopper){hoppers++;assert row<rows-1;assert p.state().getValue(HopperBlock.FACING)==facing;}
                else {chests++;assert p.state().getValue(ChestBlock.FACING)==facing;}
            }
            assert chests==2*columns*rows&&hoppers==2*columns*(rows-1);
            for(var p:plan){
                if(p.kind()==StashLayout.Kind.Hopper){
                    assert byPos.get(p.pos().relative(facing)).kind()!=StashLayout.Kind.Hopper : "Each hopper feeds the lower chest beside it";
                    assert byPos.get(p.pos().above()).kind()!=StashLayout.Kind.Hopper : "Each upper chest drains into the hopper beneath it";
                }else {
                    var partner=byPos.get(p.partner());assert partner!=null&&partner.kind()!=p.kind()&&partner.kind()!=StashLayout.Kind.Hopper;
                    assert ChestBlock.getConnectedDirection(p.state())== (p.kind()==StashLayout.Kind.LeftChest?facing.getClockWise():facing.getCounterClockWise());
                    assert !byPos.containsKey(p.pos().above()) : "Chest lids remain unobstructed";
                    var single=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,facing).setValue(ChestBlock.TYPE,ChestType.SINGLE);
                    assert !StashBuilder.matches(p,single,false) : "Completion never accepts an unpaired chest";
                    assert StashBuilder.matches(p,single,true)==(p.kind()==StashLayout.Kind.LeftChest);
                }
            }
        }
        var main=StashLayout.generate(new BlockPos(0,0,0),Direction.NORTH,128,19);
        assert main.size()==9472;
        assert main.stream().filter(p->p.kind()==StashLayout.Kind.Hopper).count()==4608;
        assert 128L*19*54==131328;
        rejects(()->StashLayout.generate(BlockPos.ZERO,Direction.UP,8,8));
        rejects(()->StashLayout.generate(BlockPos.ZERO,Direction.NORTH,0,8));
        rejects(()->StashLayout.generate(BlockPos.ZERO,Direction.NORTH,512,128));
        var model=ClassFile.of().parse(StashBuilder.class.getResourceAsStream("StashBuilder.class").readAllBytes());
        Set<String> calls=new HashSet<>();model.methods().forEach(m->m.code().ifPresent(c->c.elementStream().filter(e->e instanceof InvokeInstruction).map(e->(InvokeInstruction)e).forEach(i->calls.add(i.name().stringValue()))));
        assert calls.contains("routeToPlacement")&&calls.contains("safeVelocity")&&calls.contains("rotate");
        assert !calls.contains("startDestroyBlock")&&!calls.contains("destroyBlock") : "Builder never repairs orientation by breaking containers";
        System.out.println("Stash builder checks passed: all orientations, double pairing, hopper topology, open lids, material totals and additive-only controls.");
    }
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("Invalid layout accepted");}catch(IllegalArgumentException expected){}}
}
