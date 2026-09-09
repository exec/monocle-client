/* Monocle's single-region Litematica exporter; no world editing or Litematica runtime dependency. */
package dev.monocle.client.utils.world;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Format reference: sakura-ryoko/litematica, branch 26.2, LitematicaSchematic and LitematicaBitArray. */
public final class LitematicExporter {
    // ponytail: one loaded region, capped at 2m blocks; stream multiple chunk regions if larger exports become necessary.
    public static final int MAX_BLOCKS = 2_000_000;
    private static final long MAX_BLOCK_ENTITY_BYTES = 64L * 1024 * 1024;

    private LitematicExporter() {}

    public record Bounds(BlockPos min, BlockPos max, int sizeX, int sizeY, int sizeZ, int volume) {}

    public static Bounds bounds(BlockPos a, BlockPos b, int minY, int maxY) {
        if (a == null || b == null) throw new IllegalArgumentException("Set both selection corners first.");
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        if (min.getY() < minY || max.getY() > maxY) throw new IllegalArgumentException("Selection extends outside this dimension's build height.");
        if (min.getX() < -30_000_000 || min.getZ() < -30_000_000 || max.getX() >= 30_000_000 || max.getZ() >= 30_000_000)
            throw new IllegalArgumentException("Selection extends outside Minecraft's world bounds.");
        long x = (long) max.getX() - min.getX() + 1, y = (long) max.getY() - min.getY() + 1, z = (long) max.getZ() - min.getZ() + 1;
        if (x > MAX_BLOCKS || y > MAX_BLOCKS || z > MAX_BLOCKS || x * y * z > MAX_BLOCKS)
            throw new IllegalArgumentException("Selection exceeds the 2,000,000-block export limit. Select a smaller region.");
        return new Bounds(min, max, (int) x, (int) y, (int) z, (int) (x * y * z));
    }

    public static Capture begin(ClientLevel world, BlockPos a, BlockPos b, String name, String author) {
        requireCurrentWorld(world);
        filename(name);
        Bounds bounds = bounds(a, b, world.getMinY(), world.getMaxY());
        // Reject unavailable terrain up front, and recheck while reading: chunks can unload between ticks.
        for (int z = bounds.min.getZ() >> 4; z <= bounds.max.getZ() >> 4; z++) {
            for (int x = bounds.min.getX() >> 4; x <= bounds.max.getX() >> 4; x++) requireChunk(world, x, z);
        }
        return new Capture(world, bounds, name.strip(), author == null ? "" : author);
    }

    public static final class Capture {
        private final ClientLevel world;
        private final Bounds bounds;
        private final String name, author;
        private final long created = System.currentTimeMillis();
        private final Map<BlockState, Integer> paletteIds = new HashMap<>();
        private final ListTag palette = new ListTag(), blockEntities = new ListTag();
        private final int[] states;
        private int captured, nonAir;
        private long blockEntityBytes;
        private boolean finished;

        private Capture(ClientLevel world, Bounds bounds, String name, String author) {
            this.world = world;
            this.bounds = bounds;
            this.name = name;
            this.author = author;
            states = new int[bounds.volume];
            paletteIds.put(Blocks.AIR.defaultBlockState(), 0);
            palette.add(NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
        }

        /** Reads at most budget blocks, yielding after roughly 8 ms between small groups of reads. */
        public void step(int budget) {
            requireCurrentWorld(world);
            if (finished) throw new IllegalStateException("This capture has already been finalized.");
            if (budget <= 0) throw new IllegalArgumentException("Capture budget must be positive.");
            int end = captured + Math.min(budget, states.length - captured);
            long started = System.nanoTime();
            LevelChunk chunk = null;
            int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            while (captured < end) {
                int x = captured % bounds.sizeX;
                int z = captured / bounds.sizeX % bounds.sizeZ;
                int y = captured / (bounds.sizeX * bounds.sizeZ);
                position.set(bounds.min.getX() + x, bounds.min.getY() + y, bounds.min.getZ() + z);
                if (chunk == null || chunkX != position.getX() >> 4 || chunkZ != position.getZ() >> 4) {
                    chunkX = position.getX() >> 4;
                    chunkZ = position.getZ() >> 4;
                    chunk = requireChunk(world, chunkX, chunkZ);
                }
                BlockState state = chunk.getBlockState(position);
                Integer id = paletteIds.get(state);
                if (id == null) {
                    id = palette.size();
                    paletteIds.put(state, id);
                    palette.add(NbtUtils.writeBlockState(state));
                }
                states[captured] = id;
                if (!state.isAir()) nonAir++;
                if (state.hasBlockEntity()) {
                    BlockEntity entity = chunk.getBlockEntity(position);
                    if (entity != null) {
                        CompoundTag tag = relativeBlockEntity(entity.saveWithFullMetadata(world.registryAccess()), x, y, z);
                        blockEntityBytes += tag.sizeInBytes();
                        if (blockEntityBytes > MAX_BLOCK_ENTITY_BYTES)
                            throw new IllegalArgumentException("Block entity data exceeds 64 MiB. Select a smaller region.");
                        blockEntities.add(tag);
                    }
                }
                captured++;
                if ((captured & 63) == 0 && System.nanoTime() - started >= 8_000_000) break;
            }
        }

        public boolean done() { return captured == states.length; }
        public int captured() { return captured; }
        public int volume() { return states.length; }

        /** A detached NBT tree owned by the caller; do not mutate it while a writer is using it. */
        public CompoundTag finish() {
            requireCurrentWorld(world);
            if (!done() || finished) throw new IllegalStateException("Finish a complete capture exactly once.");
            finished = true;
            return schematic(bounds, name, author, created, nonAir, palette, states, blockEntities);
        }
    }

    private static void requireCurrentWorld(ClientLevel world) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || !client.isSameThread() || world == null || client.level != world)
            throw new IllegalStateException("Capture must run on the client thread in the original world.");
    }

    private static LevelChunk requireChunk(ClientLevel world, int x, int z) {
        LevelChunk chunk = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) throw new IllegalStateException("Selection includes an unloaded chunk at " + x + ", " + z + ". Move closer or reduce the selection, then export again.");
        return chunk;
    }

    static CompoundTag relativeBlockEntity(CompoundTag data, int x, int y, int z) {
        CompoundTag tag = data.copy();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    static CompoundTag schematic(Bounds bounds, String name, String author, long created, int nonAir, ListTag palette, int[] states, ListTag blockEntities) {
        if (states.length != bounds.volume || nonAir < 0 || nonAir > states.length) throw new IllegalArgumentException("Incomplete schematic contents.");
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", name);
        metadata.putString("Author", author);
        metadata.putString("Description", "Captured by Monocle Client. Block states and client-visible block entity data only; no entities, scheduled ticks or server-only inventory guarantees.");
        metadata.putInt("RegionCount", 1);
        metadata.putInt("TotalVolume", bounds.volume);
        metadata.putInt("TotalBlocks", nonAir);
        metadata.putLong("TimeCreated", created);
        metadata.putLong("TimeModified", created);
        metadata.put("EnclosingSize", position(bounds.sizeX, bounds.sizeY, bounds.sizeZ));
        CompoundTag region = new CompoundTag();
        region.put("Position", position(0, 0, 0));
        region.put("Size", position(bounds.sizeX, bounds.sizeY, bounds.sizeZ));
        region.put("BlockStatePalette", palette.copy());
        region.putLongArray("BlockStates", pack(states, palette.size()));
        region.put("TileEntities", blockEntities.copy());
        region.put("Entities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put("Selection", region);
        CompoundTag result = new CompoundTag();
        result.putInt("Version", 7);
        result.putInt("SubVersion", 1);
        result.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        result.put("Metadata", metadata);
        result.put("Regions", regions);
        return result;
    }

    private static CompoundTag position(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    /** Litematica uses dense entries across long boundaries, not Minecraft's modern padded bit storage. */
    static long[] pack(int[] states, int paletteSize) {
        if (states.length < 1 || states.length > MAX_BLOCKS || paletteSize < 1 || paletteSize > MAX_BLOCKS + 1)
            throw new IllegalArgumentException("Invalid block state storage size.");
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        long[] data = new long[(int) (((long) states.length * bits + 63) / 64)];
        for (int i = 0; i < states.length; i++) {
            int value = states[i];
            if (value < 0 || value >= paletteSize) throw new IllegalArgumentException("Invalid block state palette index.");
            long bit = (long) i * bits;
            int word = (int) (bit >>> 6), shift = (int) (bit & 63);
            data[word] |= (long) value << shift;
            if (shift + bits > 64) data[word + 1] |= (long) value >>> (64 - shift);
        }
        return data;
    }

    public static String filename(String name) {
        if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a schematic name, not a path.");
        String stem = name.strip();
        if (stem.toLowerCase(Locale.ROOT).endsWith(".litematic")) stem = stem.substring(0, stem.length() - 10);
        stem = stem.replaceAll("[^\\p{L}\\p{N} ._-]", "_").replaceAll("^[. ]+|[. ]+$", "");
        if (stem.isEmpty()) throw new IllegalArgumentException("Enter a schematic name.");
        if (stem.length() > 80) stem = stem.substring(0, 80).replaceAll("[. ]+$", "");
        if (stem.toUpperCase(Locale.ROOT).matches("(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) stem = "_" + stem;
        return stem + ".litematic";
    }

    /** Compress off-thread; publish a complete file without replacing any existing path. */
    public static Path write(Path schematicsDir, String name, CompoundTag snapshot) throws IOException {
        String filename = filename(name);
        if (snapshot == null || snapshot.isEmpty()) throw new IllegalArgumentException("No schematic data to save.");
        Files.createDirectories(schematicsDir);
        Path directory = schematicsDir.toRealPath();
        Path temporary = Files.createTempFile(directory, ".monocle-schematic-", ".tmp");
        try {
            NbtIo.writeCompressed(snapshot, temporary);
            for (int suffix = 1; suffix <= 9999; suffix++) {
                String candidate = suffix == 1 ? filename : filename.substring(0, filename.length() - 10) + "-" + suffix + ".litematic";
                Path target = directory.resolve(candidate);
                try {
                    // Unlike ATOMIC_MOVE (which may replace a target), link creation is atomic and never clobbers.
                    Files.createLink(target, temporary);
                    return target;
                } catch (FileAlreadyExistsException occupied) {
                    // Another export, directory or symlink owns this name; choose a new one.
                } catch (UnsupportedOperationException unsupported) {
                    throw new IOException("This filesystem does not support safe atomic schematic publication.", unsupported);
                }
            }
            throw new IOException("Too many schematics share this name. Choose another name.");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
