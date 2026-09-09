/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.world;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.events.world.ChunkDataEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WTable;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.gui.widgets.pressable.WCheckbox;
import dev.monocle.client.gui.widgets.pressable.WMinus;
import dev.monocle.client.pathing.PathManagers;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.misc.Keybind;
import dev.monocle.client.utils.misc.text.RunnableClickEvent;
import dev.monocle.client.utils.player.ChatUtils;
import dev.monocle.client.utils.render.MonocleToast;
import dev.monocle.client.utils.render.RenderUtils;
import dev.monocle.client.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class StashFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private static final List<Block> DEFAULT_SUPPORT_BLOCK_BLACKLIST = List.of(
        Blocks.COPPER_BLOCK.weathering().oxidized(),
        Blocks.CUT_COPPER.weathering().oxidized(),
        Blocks.TUFF_BRICKS,
        Blocks.COPPER_BLOCK.waxed().unaffected(),
        Blocks.COPPER_BLOCK.waxed().oxidized(),
        Blocks.CUT_COPPER.waxed().oxidized(),
        Blocks.BARREL,
        Blocks.COPPER_BULB.waxed().unaffected()
    );

    private final Setting<List<BlockEntityType<?>>> storageBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks")
        .description("Select the storage blocks to search for.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Integer> minimumStorageCount = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-storage-count")
        .description("The minimum amount of storage blocks in a chunk to record the chunk.")
        .defaultValue(4)
        .min(1)
        .sliderMin(1)
        .build()
    );

    private final Setting<Integer> minimumShulkers = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-shulkers")
        .description("Also record chunks with this many selected shulker boxes, even below the storage threshold. Zero disables this shortcut.")
        .defaultValue(1).range(0, 64).sliderRange(0, 16).build()
    );

    private final Setting<List<Block>> blacklistedBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blacklisted-support-blocks")
        .description("Blocks that prevent counting a storage block entity when it sits on them.")
        .defaultValue(DEFAULT_SUPPORT_BLOCK_BLACKLIST)
        .build()
    );

    private final Setting<Integer> minimumDistance = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-distance")
        .description("Minimum horizontal distance from 0,0 for a chunk to be recorded.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> sendNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Sends Minecraft notifications when new stashes are found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> notificationMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("notification-mode")
        .description("The mode to use for notifications.")
        .defaultValue(Mode.Both)
        .visible(sendNotifications::get)
        .build()
    );

    private final Setting<Boolean> renderTracer = sgRender.add(new BoolSetting.Builder()
        .name("render-tracer")
        .description("Renders tracers for new or manually selected findings in the current dimension.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> traceColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the stash tracer.")
        .defaultValue(new SettingColor(255, 215, 0, 255))
        .visible(renderTracer::get)
        .build()
    );

    private final Setting<Integer> traceArrivalDistance = sgRender.add(new IntSetting.Builder()
        .name("tracer-hide-at-distance")
        .description("Hide the trace when you are this close to the stash.")
        .defaultValue(16)
        .min(1)
        .sliderMin(1)
        .sliderMax(50)
        .visible(renderTracer::get)
        .build()
    );

    private final Setting<Integer> traceMaxDistance = sgRender.add(new IntSetting.Builder()
        .name("tracer-max-distance")
        .description("Hide the trace when you are farther than this distance from the stash.")
        .defaultValue(2000)
        .min(10)
        .sliderMin(50)
        .sliderMax(10000)
        .visible(renderTracer::get)
        .build()
    );

    private final Setting<Boolean> renderChunkColumn = sgRender.add(new BoolSetting.Builder()
        .name("render-chunk-column")
        .description("Renders a vertical column at the center of traced chunks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> traceColumnColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-column-color")
        .description("Color of the stash tracer column.")
        .defaultValue(new SettingColor(255, 215, 0, 100))
        .visible(renderChunkColumn::get)
        .build()
    );

    private final Setting<Keybind> clearTracesBind = sgRender.add(new KeybindSetting.Builder()
        .name("clear-traces-bind")
        .description("Keybind to clear all stash traces.")
        .defaultValue(Keybind.none())
        .build()
    );

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<ChunkPos, Vec3> tracerPositions = new HashMap<>();
    public List<Chunk> chunks = new ArrayList<>();
    private final Set<ChunkPos> scanQueue = new LinkedHashSet<>();
    private File notebookFile;
    private String scope = "", dimension = "";
    private boolean dirty, readOnly;
    private int saveTicks;
    private String search = "";
    private Sort sort = Sort.Shulkers;
    private boolean showIgnored;
    private int page;

    public StashFinder() {
        super(Categories.World, "stash-finder", "A local exploration notebook: discover storage, review findings, keep notes and track visits separately per world and dimension.");
    }

    @Override
    public void onActivate() {
        if (dirty) readOnly = false; // Allow an explicit retry after a failed disk write.
        ensureScope();
        queueLoaded();
    }

    @Override public void onDeactivate() { flush(); scanQueue.clear(); }

    @EventHandler private void onLeft(GameLeftEvent event) {
        flush();
        scanQueue.clear();
        tracerPositions.clear();
        if (dirty) return; // Retain unsaved observations for a later save retry, never write them into another world.
        scope = "";
        notebookFile = null;
        chunks = new ArrayList<>();
    }

    private void ensureScope() {
        if (mc.level == null) return;
        String world = worldIdentity();
        String nextDimension = mc.level.dimension().identifier().toString();
        String nextScope = world + "\n" + nextDimension;
        if (scope.equals(nextScope)) return;
        flush();
        if (dirty) return;
        scope = nextScope;
        dimension = nextDimension;
        notebookFile = new File(new File(MonocleClient.FOLDER, "stashes/notebooks"), scopeId(world, dimension) + ".json");
        chunks = new ArrayList<>();
        tracerPositions.clear();
        scanQueue.clear();
        dirty = readOnly = false;
        page = saveTicks = 0;
        try { chunks = readNotebook(notebookFile.toPath()); }
        catch (Exception e) {
            readOnly = true;
            error("Cannot read stash notebook; existing file is protected from writes. Check the log.");
            MonocleClient.LOG.error("Cannot load stash notebook {}", notebookFile, e);
        }
        queueLoaded();
    }

    private void queueLoaded() {
        if (!isCurrentScope(scope)) return;
        for (var chunk : Utils.chunks()) {
            if (scanQueue.size() >= 4096) break;
            if (chunk != null) scanQueue.add(chunk.getPos());
        }
    }

    private boolean isCurrentScope(String expected) {
        return mc.level != null && scope.equals(expected)
            && expected.equals(worldIdentity() + "\n" + mc.level.dimension().identifier());
    }

    private String worldIdentity() {
        // Utils calls every Realm "realms"; use its actual server address so different Realms cannot share findings.
        return !mc.isLocalServer() && mc.getCurrentServer() != null ? mc.getCurrentServer().ip : Utils.getWorldName();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null) return;
        ensureScope();
        if (!isCurrentScope(scope)) return;
        if (clearTracesBind.get().isPressed()) tracerPositions.clear();
        for (int i = 0; i < 4 && !scanQueue.isEmpty(); i++) {
            ChunkPos pos = scanQueue.iterator().next();
            scanQueue.remove(pos);
            LevelChunk chunk = mc.level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
            if (chunk != null) scan(chunk);
        }
        if (++saveTicks >= 100) { flush(); saveTicks = 0; }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        ensureScope();
        if (isCurrentScope(scope) && scanQueue.size() < 4096) scanQueue.add(event.chunk().getPos());
    }

    private void scan(LevelChunk loaded) {
        if (readOnly) return;
        // Check the distance.
        double chunkXAbs = Math.abs(loaded.getPos().x() * 16.0);
        double chunkZAbs = Math.abs(loaded.getPos().z() * 16.0);
        if (Math.sqrt(chunkXAbs * chunkXAbs + chunkZAbs * chunkZAbs) < minimumDistance.get()) return;

        Chunk chunk = new Chunk(loaded.getPos());

        List<Block> blockBlacklist = blacklistedBlocks.get();

        for (BlockEntity blockEntity : loaded.getBlockEntities().values()) {
            if (!storageBlocks.get().contains(blockEntity.getType())) continue;

            if (!blockBlacklist.isEmpty()) {
                BlockPos below = blockEntity.getBlockPos().below();
                if (blockBlacklist.contains(loaded.getBlockState(below).getBlock())) continue;
            }

            switch (blockEntity) {
                case ChestBlockEntity _ -> chunk.chests++;
                case BarrelBlockEntity _ -> chunk.barrels++;
                case ShulkerBoxBlockEntity _ -> chunk.shulkers++;
                case EnderChestBlockEntity _ -> chunk.enderChests++;
                case AbstractFurnaceBlockEntity _ -> chunk.furnaces++;
                case DispenserBlockEntity _ -> chunk.dispensersDroppers++;
                case HopperBlockEntity _ -> chunk.hoppers++;
                default -> chunk.otherStorage++;
            }
        }

        int existing = chunks.indexOf(chunk);
        if (qualifies(chunk, minimumStorageCount.get(), minimumShulkers.get()) || existing >= 0) {
            Chunk prevChunk = null;
            int i = existing;

            if (i < 0) {
                if (chunks.size() >= 10000) return;
                chunks.add(chunk);
            } else prevChunk = chunks.set(i, chunk);
            retainReview(chunk, prevChunk, System.currentTimeMillis());
            boolean changed = prevChunk == null || !chunk.countsEqual(prevChunk);
            dirty = true;

            if (renderTracer.get() && changed && chunk.review == Review.New && qualifies(chunk, minimumStorageCount.get(), minimumShulkers.get())) {
                double y = mc.player != null ? mc.player.getEyeY() : 0.0;
                tracerPositions.put(chunk.chunkPos, new Vec3(chunk.x, y, chunk.z));
            }

            if (sendNotifications.get() && changed && chunk.review == Review.New && qualifies(chunk, minimumStorageCount.get(), minimumShulkers.get())) {
                switch (notificationMode.get()) {
                    case Chat -> sendChatNotification(chunk);
                    case Toast -> {
                        MonocleToast toast = new MonocleToast.Builder(title).icon(Items.CHEST).text("Found Stash!").build();
                        mc.gui.toastManager().addToast(toast);
                    }
                    case Both -> {
                        sendChatNotification(chunk);
                        MonocleToast toast = new MonocleToast.Builder(title).icon(Items.CHEST).text("Found Stash!").build();
                        mc.gui.toastManager().addToast(toast);
                    }
                }
            }
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        ensureScope();
        String openedScope = scope;
        WVerticalList list = theme.verticalList();
        list.add(theme.label("Survey notebook · " + (scope.isEmpty() ? "Join a world to scan" : dimension)));
        list.add(theme.label("Observed storage blocks, not inventory contents. Double chests count as two blocks.", 540));
        if (readOnly) list.add(theme.label("Read-only: loading or saving failed. Original file is protected; check the log."));
        WHorizontalList filters = list.add(theme.horizontalList()).expandX().widget();
        WTextBox query = filters.add(theme.textBox(search, "Coordinates or note")).minWidth(190).expandX().widget();
        WDropdown<Sort> ordering = filters.add(theme.dropdown(Sort.values(), sort)).widget();
        WCheckbox ignored = filters.add(theme.checkbox(showIgnored)).widget();
        ignored.tooltip = "Include ignored findings";
        filters.add(theme.label("Show ignored"));
        WHorizontalList actions = list.add(theme.horizontalList()).widget();
        WButton refresh = actions.add(theme.button("Refresh / Scan loaded")).widget();
        WButton resetTracers = actions.add(theme.button("Clear traces")).widget();
        WButton export = actions.add(theme.button("Export CSV")).widget();
        WTable table = list.add(theme.table()).expandX().widget();
        Runnable redraw = () -> {
            table.clear();
            if (!isCurrentScope(openedScope)) { table.add(theme.label("Join the original world or reopen this notebook.")); return; }
            fillTable(theme, table, openedScope);
        };
        query.action = () -> { search = query.get(); page = 0; redraw.run(); };
        ordering.action = () -> { sort = ordering.get(); page = 0; redraw.run(); };
        ignored.action = () -> { showIgnored = ignored.checked; page = 0; redraw.run(); };
        refresh.action = () -> { if (isCurrentScope(openedScope)) queueLoaded(); redraw.run(); };
        resetTracers.action = () -> { tracerPositions.clear(); redraw.run(); };
        export.action = () -> {
            if (notebookFile == null || !isCurrentScope(openedScope)) return;
            try {
                Files.createDirectories(notebookFile.toPath().getParent());
                Path output = Files.createTempFile(notebookFile.toPath().getParent(), "survey-", ".csv");
                exportCsv(output, chunks, dimension);
                info("Exported survey to %s", output);
            } catch (IOException e) { error("Could not export survey: %s", e.getMessage()); }
        };
        WButton legacy = list.add(theme.button("Import old findings into this dimension…")).widget();
        legacy.tooltip = "Old stash files have no dimension information. Confirm only if they belong here. The source file is never changed.";
        legacy.action = () -> {
            if (!isCurrentScope(openedScope) || readOnly || notebookFile == null) return;
            if (!legacy.getText().startsWith("Confirm")) { legacy.set("Confirm import into " + dimension); return; }
            try {
                File old = new File(new File(new File(MonocleClient.FOLDER, "stashes"), Utils.getFileWorldName()), "stashes.json");
                if (!old.exists()) { warning("No legacy JSON notebook found. Any old CSV remains untouched."); return; }
                for (Chunk chunk : readNotebook(old.toPath())) if (!chunks.contains(chunk) && chunks.size() < 10000) chunks.add(chunk);
                dirty = true;
                flush();
                legacy.set("Import old findings into this dimension…");
                redraw.run();
            } catch (Exception e) { error("Legacy import failed; original file is unchanged: %s", e.getMessage()); }
        };
        redraw.run();
        return list;
    }

    private void fillTable(GuiTheme theme, WTable table, String openedScope) {
        double x = mc.player == null ? 0 : mc.player.getX(), z = mc.player == null ? 0 : mc.player.getZ();
        List<Chunk> results = findings(chunks, search, showIgnored, sort, x, z);
        int pages = Math.max(1, (results.size() + 49) / 50);
        page = Math.min(page, pages - 1);
        table.add(theme.label(results.size() + " findings · page " + (page + 1) + "/" + pages));
        WButton previous = table.add(theme.button("Previous")).widget();
        WButton next = table.add(theme.button("Next")).widget();
        previous.action = () -> { if (!isCurrentScope(openedScope)) return; page = Math.max(0, page - 1); table.clear(); fillTable(theme, table, openedScope); };
        next.action = () -> { if (!isCurrentScope(openedScope)) return; page = Math.min(pages - 1, page + 1); table.clear(); fillTable(theme, table, openedScope); };
        table.row();
        for (Chunk chunk : results.subList(page * 50, Math.min(results.size(), (page + 1) * 50))) {
            table.add(theme.label(chunk.x + ", " + chunk.z + " · " + Math.round(Math.hypot(chunk.x - x, chunk.z - z)) + "m")).padRight(8);
            table.add(theme.label(chunk.getTotal() + " blocks · " + chunk.shulkers + " shulkers · " + chunk.review)).padRight(8);

            WCheckbox visible = table.add(theme.checkbox(tracerPositions.containsKey(chunk.chunkPos))).widget();
            visible.action = () -> {
                if (!isCurrentScope(openedScope)) return;
                if (visible.checked) {
                    double y = mc.player != null ? mc.player.getEyeY() : 0.0;
                    tracerPositions.put(chunk.chunkPos, new Vec3(chunk.x, y, chunk.z));
                } else tracerPositions.remove(chunk.chunkPos);
            };

            WButton open = table.add(theme.button("Review")).widget();
            open.action = () -> {
                int index = chunks.indexOf(chunk);
                if (isCurrentScope(openedScope) && index >= 0) mc.gui.setScreen(new ChunkScreen(theme, chunks.get(index), openedScope));
            };

            WButton gotoBtn = table.add(theme.button("Goto")).widget();
            gotoBtn.action = () -> { if (isCurrentScope(openedScope)) PathManagers.get().moveTo(new BlockPos(chunk.x, 0, chunk.z), true); };

            WMinus delete = table.add(theme.minus()).widget();
            delete.tooltip = "Forget this finding and its note. A later scan can rediscover its storage blocks.";
            delete.action = () -> {
                if (!isCurrentScope(openedScope) || readOnly) return;
                if (chunks.remove(chunk)) {
                    tracerPositions.remove(chunk.chunkPos);
                    table.clear();
                    fillTable(theme, table, openedScope);
                    dirty = true;
                    flush();
                }
            };

            table.row();
        }
    }

    private void flush() {
        if (!dirty || readOnly || notebookFile == null) return;
        try { writeNotebook(notebookFile.toPath(), chunks); dirty = false; }
        catch (IOException e) {
            readOnly = true;
            MonocleClient.LOG.error("Could not save stash notebook {}", notebookFile, e);
            error("Stash notebook could not be saved. Check disk space and the log.");
        }
    }

    static String scopeId(String world, String dimension) {
        return UUID.nameUUIDFromBytes((world.length() + ":" + world + dimension).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static boolean qualifies(Chunk chunk, int total, int shulkers) {
        return chunk.getTotal() >= total || shulkers > 0 && chunk.shulkers >= shulkers;
    }

    static void retainReview(Chunk observed, Chunk previous, long now) {
        observed.firstSeen = previous == null ? now : previous.firstSeen;
        observed.lastSeen = now;
        if (previous != null) { observed.note = previous.note; observed.review = previous.review; }
    }

    static List<Chunk> findings(List<Chunk> chunks, String query, boolean ignored, Sort sort, double x, double z) {
        String term = query.toLowerCase(Locale.ROOT).strip();
        Comparator<Chunk> order = switch (sort) {
            case Shulkers -> Comparator.<Chunk>comparingInt(c -> c.shulkers).reversed().thenComparing(Comparator.comparingInt(Chunk::getTotal).reversed());
            case Storage -> Comparator.comparingInt(Chunk::getTotal).reversed();
            case Nearest -> Comparator.comparingDouble(c -> Math.hypot(c.x - x, c.z - z));
            case Recent -> Comparator.<Chunk>comparingLong(c -> c.lastSeen).reversed();
        };
        return chunks.stream().filter(c -> ignored || c.review != Review.Ignored)
            .filter(c -> (c.x + ", " + c.z + " " + c.note).toLowerCase(Locale.ROOT).contains(term))
            .sorted(order.thenComparingInt(c -> c.x).thenComparingInt(c -> c.z)).toList();
    }

    static List<Chunk> readNotebook(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        if (Files.size(file) > 64 * 1024 * 1024) throw new IOException("Notebook exceeds 64 MiB");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Chunk> result = GSON.fromJson(reader, new TypeToken<List<Chunk>>() {}.getType());
            validate(result);
            return new ArrayList<>(result);
        } catch (RuntimeException e) { throw new IOException("Invalid stash notebook", e); }
    }

    private static void validate(List<Chunk> records) throws IOException {
        if (records == null || records.size() > 10000) throw new IOException("Invalid notebook size");
        Set<ChunkPos> seen = new HashSet<>();
        for (Chunk c : records) {
            if (c == null || c.chunkPos == null || !seen.add(c.chunkPos)
                || Math.abs((long) c.chunkPos.x()) > 1875000 || Math.abs((long) c.chunkPos.z()) > 1875000
                || c.firstSeen < 0 || c.lastSeen < 0) throw new IOException("Invalid or duplicate finding");
            for (int count : new int[] {c.chests, c.barrels, c.shulkers, c.enderChests, c.furnaces, c.dispensersDroppers, c.hoppers, c.otherStorage})
                if (count < 0 || count > 1000000) throw new IOException("Invalid storage count");
            if (c.note == null) c.note = "";
            if (c.note.length() > 512) throw new IOException("Finding note exceeds 512 characters");
            if (c.review == null) c.review = Review.New;
            c.calculatePos();
        }
    }

    static void writeNotebook(Path file, List<Chunk> records) throws IOException {
        validate(records);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "stash-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { GSON.toJson(records, writer); }
            if (Files.size(temporary) > 64 * 1024 * 1024) throw new IOException("Notebook exceeds 64 MiB");
            // Do not fall back to truncating the old notebook on filesystems without atomic replacement.
            Files.move(temporary, file.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static String csvText(String text) {
        // Keep notes as text when opened by a spreadsheet, including user-supplied formulas.
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    static void exportCsv(Path file, List<Chunk> chunks, String dimension) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("Dimension,X,Z,Chests,Barrels,Shulkers,EnderChests,Furnaces,DispensersDroppers,Hoppers,OtherStorage,Review,FirstSeen,LastSeen,Note\n");
            for (Chunk c : chunks) writer.write(csvText(dimension) + "," + c.x + "," + c.z + "," + c.chests + "," + c.barrels + "," + c.shulkers
                + "," + c.enderChests + "," + c.furnaces + "," + c.dispensersDroppers + "," + c.hoppers + "," + c.otherStorage + "," + c.review + "," + c.firstSeen + "," + c.lastSeen + "," + csvText(c.note) + "\n");
        }
    }

    @Override
    public String getInfoString() {
        return String.valueOf(chunks.size());
    }

    private void sendChatNotification(Chunk chunk) {
        MutableComponent coords = Component.literal(chunk.x + ", " + chunk.z)
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.WHITE)
                .applyFormat(ChatFormatting.UNDERLINE)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy coordinates")))
                .withClickEvent(new RunnableClickEvent(() -> mc.keyboardHandler.setClipboard(chunk.x + " " + chunk.z))));

        MutableComponent message = Component.literal("Found stash at ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("[").withStyle(ChatFormatting.GRAY))
            .append(coords)
            .append(Component.literal("]").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" · " + dimension + " · " + chunk.getTotal() + " storage / " + chunk.shulkers + " shulkers").withStyle(ChatFormatting.GRAY));

        ChatUtils.sendMsg(message);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (tracerPositions.isEmpty() || mc.player == null || !isCurrentScope(scope)) return;

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        tracerPositions.entrySet().removeIf(entry -> {
            Vec3 pos = entry.getValue();
            double horizontalDist = Math.hypot(pos.x - playerX, pos.z - playerZ);
            return horizontalDist <= traceArrivalDistance.get();
        });

        if (!renderTracer.get() && !renderChunkColumn.get()) return;

        for (Vec3 pos : tracerPositions.values()) {
            double horizontalDist = Math.hypot(pos.x - playerX, pos.z - playerZ);
            if (horizontalDist > traceMaxDistance.get()) continue;

            if (renderTracer.get()) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, pos.x, mc.player.getEyeY(), pos.z, traceColor.get()
                );
            }

            if (renderChunkColumn.get()) {
                double x1 = pos.x - 0.5;
                double x2 = pos.x + 0.5;
                double z1 = pos.z - 0.5;
                double z2 = pos.z + 0.5;

                int bottomY = mc.level.getMinY();
                int topY = bottomY + mc.level.dimensionType().height();

                event.renderer.line(x1, bottomY, z1, x1, topY, z1, traceColumnColor.get());
                event.renderer.line(x1, bottomY, z2, x1, topY, z2, traceColumnColor.get());
                event.renderer.line(x2, bottomY, z1, x2, topY, z1, traceColumnColor.get());
                event.renderer.line(x2, bottomY, z2, x2, topY, z2, traceColumnColor.get());
            }
        }
    }

    public enum Mode {
        Chat,
        Toast,
        Both
    }

    public enum Sort { Shulkers, Storage, Nearest, Recent }
    public enum Review { New, Visited, Ignored }

    public static class Chunk {
        public ChunkPos chunkPos;
        public transient int x, z;
        public int chests, barrels, shulkers, enderChests, furnaces, dispensersDroppers, hoppers, otherStorage;
        public long firstSeen, lastSeen;
        public String note = "";
        public Review review = Review.New;

        public Chunk(ChunkPos chunkPos) {
            this.chunkPos = chunkPos;

            calculatePos();
        }

        public void calculatePos() {
            x = chunkPos.x() * 16 + 8;
            z = chunkPos.z() * 16 + 8;
        }

        public int getTotal() {
            return chests + barrels + shulkers + enderChests + furnaces + dispensersDroppers + hoppers + otherStorage;
        }

        public boolean countsEqual(Chunk c) {
            if (c == null) return false;
            return chests == c.chests && barrels == c.barrels && shulkers == c.shulkers && enderChests == c.enderChests && furnaces == c.furnaces && dispensersDroppers == c.dispensersDroppers && hoppers == c.hoppers && otherStorage == c.otherStorage;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Chunk chunk = (Chunk) o;
            return Objects.equals(chunkPos, chunk.chunkPos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chunkPos);
        }
    }

    private class ChunkScreen extends WindowScreen {
        private final Chunk chunk;
        private final String openedScope;

        public ChunkScreen(GuiTheme theme, Chunk chunk, String openedScope) {
            super(theme, "Chunk at " + chunk.x + ", " + chunk.z);

            this.chunk = chunk;
            this.openedScope = openedScope;
        }

        @Override
        public void initWidgets() {
            add(theme.label(dimension + " · First seen: " + seenTime(chunk.firstSeen) + " · Last seen: " + seenTime(chunk.lastSeen), 550));
            WDropdown<Review> review = add(theme.dropdown(Review.values(), chunk.review)).widget();
            WTextBox note = add(theme.textBox(chunk.note, "Notes (up to 512 characters)")).minWidth(350).expandX().widget();
            WButton save = add(theme.button("Save review / note")).widget();
            save.action = () -> {
                if (!isCurrentScope(openedScope) || readOnly) return;
                int index = chunks.indexOf(chunk);
                if (index < 0) return;
                Chunk current = chunks.get(index);
                if (note.get().length() > 512) { error("Keep the note within 512 characters."); return; }
                current.note = note.get();
                current.review = review.get();
                if (current.review != Review.New) tracerPositions.remove(current.chunkPos);
                dirty = true;
                flush();
                save.set(dirty ? "Save failed — check log" : "Saved");
            };
            WButton copy = add(theme.button("Copy coordinates")).widget();
            copy.action = () -> mc.keyboardHandler.setClipboard(chunk.x + " " + chunk.z);
            WTable t = add(theme.table()).expandX().widget();

            // Total
            t.add(theme.label("Total:"));
            t.add(theme.label(chunk.getTotal() + ""));
            t.row();

            t.add(theme.horizontalSeparator()).expandX();
            t.row();

            // Separate
            t.add(theme.label("Chests:"));
            t.add(theme.label(chunk.chests + ""));
            t.row();

            t.add(theme.label("Barrels:"));
            t.add(theme.label(chunk.barrels + ""));
            t.row();

            t.add(theme.label("Shulkers:"));
            t.add(theme.label(chunk.shulkers + ""));
            t.row();

            t.add(theme.label("Ender Chests:"));
            t.add(theme.label(chunk.enderChests + ""));
            t.row();

            t.add(theme.label("Furnaces:"));
            t.add(theme.label(chunk.furnaces + ""));
            t.row();

            t.add(theme.label("Dispensers and droppers:"));
            t.add(theme.label(chunk.dispensersDroppers + ""));
            t.row();

            t.add(theme.label("Hoppers:"));
            t.add(theme.label(chunk.hoppers + ""));
            t.row();
            t.add(theme.label("Other selected storage:"));
            t.add(theme.label(chunk.otherStorage + ""));
        }
    }

    private static String seenTime(long time) {
        return time == 0 ? "Unknown (legacy)" : java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
