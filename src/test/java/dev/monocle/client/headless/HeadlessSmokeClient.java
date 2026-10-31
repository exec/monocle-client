package dev.monocle.client.headless;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.utils.misc.HeadlessMode;
import dev.monocle.client.utils.network.WorldReadiness;
import dev.monocle.coordinator.TaskFiles;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.*;
import com.google.gson.JsonObject;

/** Opt-in disposable single-player integration fixture; excluded from the production client JAR. */
public final class HeadlessSmokeClient implements ClientModInitializer {
    private boolean opened, connected;
    private int worldTicks;
    @Override public void onInitializeClient() {
        if (!HeadlessMode.enabled() || !Boolean.getBoolean("monocle.headless.smoke"))
            throw new IllegalStateException("The smoke fixture requires explicit headless test flags");
        MonocleClient.EVENT_BUS.registerLambdaFactory("dev.monocle.client.headless", (method, type) -> (java.lang.invoke.MethodHandles.Lookup) method.invoke(null,type,java.lang.invoke.MethodHandles.lookup()));
        MonocleClient.EVENT_BUS.subscribe(this);
    }
    @EventHandler private void tick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.getUser().getName().equals("HeadlessSmoke")) throw new IllegalStateException("Use a disposable smoke account");
        if (!opened && mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null) {
            opened = true;
            var settings = new LevelSettings("Synthetic headless smoke", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("synthetic-headless-smoke",settings,WorldOptions.testWorldWithRandomSeed(),WorldPresets::createTestWorldDimensions,mc.gui.screen());
        }
        if (mc.level == null || mc.player == null || !WorldReadiness.INSTANCE.ready()) return;
        if (++worldTicks != 40) return;
        if (!mc.hasSingleplayerServer()) throw new IllegalStateException("Smoke fixture must remain in single player");
        JsonObject result = new JsonObject();result.addProperty("version",1);result.addProperty("worldTicks",worldTicks);
        result.addProperty("playerTicks",mc.player.tickCount);result.addProperty("inventorySlots",mc.player.getInventory().getContainerSize());
        result.addProperty("headless",HeadlessMode.enabled());result.addProperty("worldReady",true);
        String config = System.getProperty("monocle.headless.smoke.host","");
        if (!config.isEmpty()) {
            JsonObject host = TaskFiles.read(Path.of(config));
            if (!host.get("bind").getAsString().equals("127.0.0.1")) throw new IllegalArgumentException("Smoke host must bind to loopback");
            Bots bots = Bots.get();bots.crewKey.set(host.getAsJsonObject("crews").get("Default").getAsString());
            bots.connectWorker("127.0.0.1",host.get("workerPort").getAsInt());connected = true;
        }
        result.addProperty("hostRequested",connected);
        TaskFiles.write(mc.gameDirectory.toPath().resolve("headless-smoke-result.json"),result);
        MonocleClient.LOG.info("Headless smoke world ready with {} inventory slots",result.get("inventorySlots"));
    }
}
