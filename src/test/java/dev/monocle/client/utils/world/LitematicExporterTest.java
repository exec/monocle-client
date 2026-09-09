package dev.monocle.client.utils.world;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;

/** Native NBT roundtrips and independently decoded bit storage; no running Minecraft or Litematica required. */
public final class LitematicExporterTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with assertions enabled (-ea).");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        geometry();
        densePacking();
        CompoundTag schematic = format();
        filenamesAndFiles(schematic);
        captureGuards();
        System.out.println("Litematic export checks passed: inclusive bounds, dense palette packing, native block properties and sign data, format7 metadata, safe filenames, atomic no-overwrite publication and capture thread/load guards.");
    }

    private static void geometry() {
        BlockPos a = new BlockPos(-1, 66, 2), b = new BlockPos(-4, 64, 0);
        var bounds = LitematicExporter.bounds(a, b, -64, 319);
        assert bounds.min().equals(b) && bounds.max().equals(a);
        assert bounds.sizeX() == 4 && bounds.sizeY() == 3 && bounds.sizeZ() == 3 && bounds.volume() == 36;
        assert bounds.equals(LitematicExporter.bounds(b, a, -64, 319)) : "Corner order must not change exported contents";
        assert LitematicExporter.bounds(a, a, -64, 319).volume() == 1;
        assert LitematicExporter.bounds(BlockPos.ZERO, new BlockPos(199, 99, 99), 0, 319).volume() == LitematicExporter.MAX_BLOCKS;
        invalid(() -> LitematicExporter.bounds(BlockPos.ZERO, new BlockPos(200, 99, 99), 0, 319));
        invalid(() -> LitematicExporter.bounds(new BlockPos(-29_999_999, 0, -29_999_999), new BlockPos(29_999_999, 319, 29_999_999), 0, 319));
        invalid(() -> LitematicExporter.bounds(BlockPos.ZERO, new BlockPos(0, 320, 0), -64, 319));
        invalid(() -> LitematicExporter.bounds(BlockPos.ZERO, new BlockPos(0, -65, 0), -64, 319));
        invalid(() -> LitematicExporter.bounds(BlockPos.ZERO, new BlockPos(Integer.MAX_VALUE, 0, 0), -64, 319));
        invalid(() -> LitematicExporter.bounds(null, BlockPos.ZERO, -64, 319));
    }

    private static void densePacking() {
        Random random = new Random(92837);
        for (int palette : new int[] {1, 2, 3, 4, 5, 8, 9, 16, 17, 65, 129, 513}) {
            int bits = Math.max(2, Integer.toBinaryString(palette - 1).length());
            for (int length : new int[] {1, 21, 63, 64, 65, 137, 1000}) {
                int[] states = new int[length];
                for (int i = 0; i < length; i++) states[i] = random.nextInt(palette);
                long[] packed = LitematicExporter.pack(states, palette);
                assert packed.length == ((long) length * bits + 63) / 64 : "Dense packing must not pad each long to a whole number of entries";
                for (int i = 0; i < length; i++) assert readBits(packed, (long) i * bits, bits) == states[i]
                    : "Cross-long entry mismatch at palette=" + palette + ", index=" + i;
                for (long bit = (long) length * bits; bit < (long) packed.length * 64; bit++) assert readBits(packed, bit, 1) == 0;
            }
        }
        invalid(() -> LitematicExporter.pack(new int[] {-1}, 1));
        invalid(() -> LitematicExporter.pack(new int[] {1}, 1));
        invalid(() -> LitematicExporter.pack(new int[0], 1));
        invalid(() -> LitematicExporter.pack(new int[] {0}, 0));
    }

    // Bit-by-bit reader intentionally independent of the production word-splitting writer.
    private static int readBits(long[] data, long start, int length) {
        int value = 0;
        for (int bit = 0; bit < length; bit++) {
            long absolute = start + bit;
            if ((data[(int) (absolute / 64)] & (1L << (absolute % 64))) != 0) value |= 1 << bit;
        }
        return value;
    }

    private static CompoundTag format() {
        var bounds = LitematicExporter.bounds(new BlockPos(-1, 66, 2), new BlockPos(-4, 64, 0), -64, 319);
        List<BlockState> statePalette = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
            Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST),
            Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, 7));
        ListTag palette = new ListTag();
        for (var state : statePalette) palette.add(NbtUtils.writeBlockState(state));
        int[] states = new int[bounds.volume()];
        int nonAir = 0;
        for (int i = 0; i < states.length; i++) {
            states[i] = i % statePalette.size();
            if (states[i] != 0) nonAir++;
        }
        var registries = VanillaRegistries.createLookup();
        SignBlockEntity sign = new SignBlockEntity(new BlockPos(-4, 64, 1), statePalette.get(4));
        CompoundTag originalSign = sign.saveWithFullMetadata(registries);
        SignText text = new SignText().setMessage(0, Component.literal("Monocle build")).setHasGlowingText(true);
        originalSign.put("front_text", SignText.DIRECT_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), text).getOrThrow());
        ListTag entities = new ListTag();
        CompoundTag relativeSign = LitematicExporter.relativeBlockEntity(originalSign, 0, 0, 1);
        entities.add(relativeSign);
        assert originalSign.getIntOr("x", 0) == -4 && originalSign.getIntOr("y", 0) == 64 : "Relative coordinates cannot mutate the captured original tag";
        assert relativeSign.getIntOr("x", -1) == 0 && relativeSign.getIntOr("y", -1) == 0 && relativeSign.getIntOr("z", -1) == 1;
        CompoundTag result = LitematicExporter.schematic(bounds, "Test build", "Monocle tester", 123456789L, nonAir, palette, states, entities);
        assert result.getIntOr("Version", -1) == 7 && result.getIntOr("SubVersion", -1) == 1;
        assert result.getIntOr("MinecraftDataVersion", -1) == SharedConstants.getCurrentVersion().dataVersion().version();
        CompoundTag metadata = result.getCompoundOrEmpty("Metadata");
        assert metadata.getIntOr("TotalVolume", -1) == 36 && metadata.getIntOr("TotalBlocks", -1) == nonAir;
        assert metadata.getIntOr("RegionCount", -1) == 1 && metadata.getLongOr("TimeCreated", -1) == 123456789L;
        assert metadata.getStringOr("Author", "").equals("Monocle tester");
        CompoundTag region = result.getCompoundOrEmpty("Regions").getCompoundOrEmpty("Selection");
        assert region.getCompoundOrEmpty("Position").getIntOr("x", -1) == 0;
        assert region.getCompoundOrEmpty("Size").getIntOr("x", -1) == 4;
        assert region.getListOrEmpty("Entities").isEmpty() && region.getListOrEmpty("PendingBlockTicks").isEmpty() && region.getListOrEmpty("PendingFluidTicks").isEmpty();
        ListTag savedPalette = region.getListOrEmpty("BlockStatePalette");
        long[] packed = region.getLongArray("BlockStates").orElseThrow();
        for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) for (int x = 0; x < 4; x++) {
            int index = (y * 3 + z) * 4 + x;
            int id = readBits(packed, index * 3L, 3);
            assert NbtUtils.readBlockState(BuiltInRegistries.BLOCK, savedPalette.getCompoundOrEmpty(id)) == statePalette.get(states[index]);
        }
        CompoundTag savedSign = region.getListOrEmpty("TileEntities").getCompoundOrEmpty(0);
        SignText restoredText = SignText.DIRECT_CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), savedSign.get("front_text")).getOrThrow();
        assert restoredText.getMessage(0, false).getString().equals("Monocle build") && restoredText.hasGlowingText();
        relativeSign.putString("id", "changed");
        palette.clear();
        states[0] = 4;
        assert !savedSign.getStringOr("id", "").equals("changed") && savedPalette.size() == 5 && readBits(packed, 0, 3) == 0 : "Finished NBT is detached from mutable capture buffers";
        return result;
    }

    private static void filenamesAndFiles(CompoundTag snapshot) throws Exception {
        assert LitematicExporter.filename("Road build").equals("Road build.litematic");
        assert LitematicExporter.filename("  Road.LITEMATIC  ").equals("Road.litematic");
        assert LitematicExporter.filename("Road: rev?2").equals("Road_ rev_2.litematic");
        assert LitematicExporter.filename("CON").equals("_CON.litematic");
        assert LitematicExporter.filename("x".repeat(100)).length() == 90;
        for (String invalid : List.of("", " ", ".litematic", "..", "../escape", "folder/name", "folder\\name", "line\nbreak")) invalid(() -> LitematicExporter.filename(invalid));
        Path directory = Files.createTempDirectory("monocle-litematic-check-");
        try {
            Path first = LitematicExporter.write(directory, "Build", snapshot);
            assert first.getParent().equals(directory.toRealPath()) && first.getFileName().toString().equals("Build.litematic");
            assert snapshot.equals(NbtIo.readCompressed(first, NbtAccounter.create(64L * 1024 * 1024)));
            byte[] original = Files.readAllBytes(first);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var a = workers.submit(() -> LitematicExporter.write(directory, "Build", snapshot));
                var b = workers.submit(() -> LitematicExporter.write(directory, "Build", snapshot));
                Path second = a.get(), third = b.get();
                assert !second.equals(first) && !third.equals(first) && !second.equals(third) : "Concurrent exports must publish unique complete files";
                assert snapshot.equals(NbtIo.readCompressed(second, NbtAccounter.create(64L * 1024 * 1024)));
                assert snapshot.equals(NbtIo.readCompressed(third, NbtAccounter.create(64L * 1024 * 1024)));
            }
            assert java.util.Arrays.equals(original, Files.readAllBytes(first)) : "Existing exports cannot be overwritten";
            Files.createSymbolicLink(directory.resolve("Link.litematic"), first);
            Path linked = LitematicExporter.write(directory, "Link", snapshot);
            assert linked.getFileName().toString().equals("Link-2.litematic") && java.util.Arrays.equals(original, Files.readAllBytes(first));
            try (var files = Files.list(directory)) { assert files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")) : "Only complete final schematics remain"; }
        } finally {
            // This test owns precisely the directory returned by createTempDirectory; do not follow symlinks.
            try (var files = Files.walk(directory)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }

    private static void captureGuards() throws Exception {
        try (var bytes = LitematicExporter.Capture.class.getResourceAsStream("LitematicExporter$Capture.class")) {
            if (bytes == null) throw new AssertionError("Missing capture class");
            var step = ClassFile.of().parse(bytes.readAllBytes()).methods().stream().filter(method -> method.methodName().equalsString("step")).findFirst().orElseThrow();
            var calls = step.code().orElseThrow().elementList().stream().filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
            assert calls.getFirst().name().equalsString("requireCurrentWorld") : "Every bounded read must first verify the current world and client thread";
            int load = -1, read = -1;
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).name().equalsString("requireChunk")) load = i;
                if (calls.get(i).name().equalsString("getBlockState")) read = i;
                assert !calls.get(i).name().equalsString("setBlock") && !calls.get(i).name().equalsString("setBlockState") : "Export is read-only";
            }
            assert load >= 0 && read > load : "An unloaded chunk must never be silently captured as air";
        }
    }

    private static void invalid(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid export input was accepted");
    }
}
