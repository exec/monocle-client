/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client;

import dev.monocle.client.addons.AddonManager;
import dev.monocle.client.addons.MonocleAddon;
import dev.monocle.client.events.game.OpenScreenEvent;
import dev.monocle.client.events.monocle.KeyInputEvent;
import dev.monocle.client.events.monocle.MouseClickEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.gui.GuiThemes;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.gui.tabs.Tabs;
import dev.monocle.client.systems.Systems;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.systems.hud.screens.AddHudElementScreen;
import dev.monocle.client.systems.hud.screens.HudEditorScreen;
import dev.monocle.client.systems.hud.screens.HudElementScreen;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.DiscordPresence;
import dev.monocle.client.utils.PostInit;
import dev.monocle.client.utils.PreInit;
import dev.monocle.client.utils.ReflectInit;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.misc.Version;
import dev.monocle.client.utils.misc.input.KeyAction;
import dev.monocle.client.utils.misc.input.KeyBinds;
import meteordevelopment.orbit.EventBus;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import meteordevelopment.orbit.IEventBus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MonocleClient implements ClientModInitializer {
    public static final String MOD_ID = "monocle-client";
    public static final ModMetadata MOD_META;
    public static final String NAME;
    public static final Version VERSION;
    public static final String BUILD_NUMBER;

    public static MonocleClient INSTANCE;
    public static MonocleAddon ADDON;

    public static Minecraft mc;
    public static final IEventBus EVENT_BUS = new EventBus();
    public static final File FOLDER = FabricLoader.getInstance().getGameDir().resolve(MOD_ID).toFile();
    public static final Logger LOG;

    static {
        MOD_META = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getMetadata();

        NAME = MOD_META.getName();
        LOG = LoggerFactory.getLogger(NAME);

        String versionString = MOD_META.getVersion().getFriendlyString();
        Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(versionString);
        // When building and running through IntelliJ and not Gradle it doesn't replace the version so just use a dummy
        versionString = !matcher.find() ? "0.0.0" : "%s.%s.%s".formatted(matcher.group(1), matcher.group(2), matcher.group(3));

        VERSION = new Version(versionString);
        BUILD_NUMBER = MOD_META.getCustomValue(MonocleClient.MOD_ID + ":build_number").getAsString();
    }

    @Override
    public void onInitializeClient() {
        if (INSTANCE == null) {
            INSTANCE = this;
            return;
        }

        // Global minecraft client accessor
        mc = Minecraft.getInstance();

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            LOG.info("Force loading mixins");
            MixinEnvironment.getCurrentEnvironment().audit();
        }

        LOG.info("Initializing {}", NAME);

        // Pre-load
        if (!FOLDER.exists()) {
            FOLDER.getParentFile().mkdirs();
            FOLDER.mkdir();
            Systems.addPreLoadTask(() -> Modules.get().get(DiscordPresence.class).enable());
        }

        // Register addons
        AddonManager.init();

        // Register event handlers
        AddonManager.ADDONS.forEach(addon -> {
            try {
                EVENT_BUS.registerLambdaFactory(addon.getPackage(), (lookupInMethod, klass) -> (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup()));
            } catch (AbstractMethodError e) {
                throw new RuntimeException("Addon \"%s\" is too old and cannot be ran.".formatted(addon.name), e);
            }
        });

        // Register init classes
        ReflectInit.registerPackages();

        // Pre init
        ReflectInit.init(PreInit.class);

        // Register module categories
        Categories.init();

        // Load systems
        Systems.init();

        // Subscribe after systems are loaded
        EVENT_BUS.subscribe(this);

        // Initialise addons
        AddonManager.ADDONS.forEach(MonocleAddon::onInitialize);

        // Sort modules after addons have added their own
        Modules.get().sortModules();

        // Load configs
        Systems.load();

        // Post init
        ReflectInit.init(PostInit.class);

        // Save on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Systems.save();
            GuiThemes.save();
        }));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.gui.screen() == null && mc.gui.overlay() == null && KeyBinds.OPEN_COMMANDS.consumeClick()) {
            mc.gui.setScreen(new ChatScreen(Config.get().prefix.get(), true));
        }
    }

    @EventHandler
    private void onKey(KeyInputEvent event) {
        if (event.action == KeyAction.Press && KeyBinds.OPEN_GUI.matches(event.input)) {
            toggleGui();
        }
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action == KeyAction.Press && KeyBinds.OPEN_GUI.matchesMouse(event.click)) {
            toggleGui();
        }
    }

    private void toggleGui() {
        if (Utils.canCloseGui()) mc.gui.screen().onClose();
        else if (Utils.canOpenGui()) Tabs.get().getFirst().openScreen(GuiThemes.get());
    }

    // Hide HUD

    private boolean wasWidgetScreen, wasHudHiddenRoot;

    @EventHandler(priority = EventPriority.LOWEST)
    private void onOpenScreen(OpenScreenEvent event) {
        if (event.screen instanceof WidgetScreen) {
            if (!wasWidgetScreen) wasHudHiddenRoot = mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden;
            if (GuiThemes.get().hideHUD() || wasHudHiddenRoot) {
                // Always show the MC HUD in the HUD editor screen since people like
                // to align some items with the hotbar or chat
                mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden = !(event.screen instanceof HudEditorScreen)
                    && !(event.screen instanceof AddHudElementScreen)
                    && !(event.screen instanceof HudElementScreen);
            }
        } else {
            if (wasWidgetScreen) mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden = wasHudHiddenRoot;
            wasHudHiddenRoot = mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden;
        }

        wasWidgetScreen = event.screen instanceof WidgetScreen;
    }

    public static Identifier identifier(String path) {
        return Identifier.fromNamespaceAndPath(MonocleClient.MOD_ID, path);
    }
}
