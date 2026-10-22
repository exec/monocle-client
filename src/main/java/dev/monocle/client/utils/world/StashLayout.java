package dev.monocle.client.utils.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.ArrayList;
import java.util.List;

/** Main's diagonal double-chest bank, rotated without changing its hopper topology. */
public final class StashLayout {
    private StashLayout() {}
    public enum Kind { LeftChest, RightChest, Hopper }
    public record Placement(BlockPos pos, Kind kind, Direction facing) {
        public BlockState state() {
            return kind==Kind.Hopper?Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,facing)
                :Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,facing)
                    .setValue(ChestBlock.TYPE,kind==Kind.LeftChest?ChestType.LEFT:ChestType.RIGHT);
        }
        public BlockPos partner() { return pos.relative(kind==Kind.LeftChest?facing.getClockWise():facing.getCounterClockWise()); }
    }
    public static List<Placement> generate(BlockPos origin,Direction facing,int columns,int rows) {
        if(origin==null||facing==null||!facing.getAxis().isHorizontal()||columns<1||columns>512||rows<1||rows>128
            ||2L*columns*(2L*rows-1)>65_536)throw new IllegalArgumentException("Use 1–512 columns, 1–128 rows and at most 65,536 blocks");
        Direction across=facing.getClockWise(),back=facing.getOpposite();
        List<Placement> result=new ArrayList<>();
        for(int row=0;row<rows;row++)for(int index=0;index<columns;index++) {
            int column=row%2==0?index:columns-1-index;
            BlockPos left=origin.relative(across,2*column).relative(back,row).above(row),right=left.relative(across);
            result.add(new Placement(left,Kind.LeftChest,facing));result.add(new Placement(right,Kind.RightChest,facing));
            if(row<rows-1){result.add(new Placement(left.relative(back),Kind.Hopper,facing));result.add(new Placement(right.relative(back),Kind.Hopper,facing));}
        }
        return List.copyOf(result);
    }
}
