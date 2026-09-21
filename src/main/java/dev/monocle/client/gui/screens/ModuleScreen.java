/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens;

import com.mojang.blaze3d.platform.MacosUtil;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.monocle.ActiveModulesChangedEvent;
import dev.monocle.client.events.monocle.ModuleBindChangedEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.utils.Cell;
import dev.monocle.client.gui.widgets.WKeybind;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WContainer;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WSection;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.gui.widgets.pressable.WCheckbox;
import dev.monocle.client.gui.widgets.pressable.WFavorite;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.systems.bots.BotProfiles;
import dev.monocle.client.systems.bots.BotScheduler;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.utils.misc.NbtUtils;
import dev.monocle.client.utils.render.prompts.OkPrompt;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.HashMap;
import java.util.Map;

import static dev.monocle.client.utils.Utils.getWindowWidth;
import static dev.monocle.client.MonocleClient.mc;
import static com.mojang.blaze3d.platform.InputConstants.*;

public class ModuleScreen extends WindowScreen {
    private static final Map<String, String> searches = new HashMap<>();
    private static final Map<String, Double> scrolls = new HashMap<>();
    private final Module module;

    private WContainer settingsContainer;
    private WKeybind keybind;
    private WCheckbox active;
    private WLabel searchStatus;
    private WLabel stateLabel;
    private WTextBox searchBox;
    private String search;
    private boolean restoredScroll;

    public ModuleScreen(GuiTheme theme, Module module) {
        super(theme, theme.favorite(module.favorite), module.title);
        ((WFavorite) window.icon).action = () -> module.favorite = ((WFavorite) window.icon).checked;

        this.module = module;
        search = searches.getOrDefault(module.name, "");
    }

    @Override protected void init() {
        super.init();
        if (!restoredScroll) {
            restoredScroll = true;
            window.view.restoreScroll(theme.scale(scrolls.getOrDefault(module.name, 0.0)));
        }
    }

    @Override protected void onClosed() {
        super.onClosed();
        searches.put(module.name, search);
        scrolls.put(module.name, window.view.scrollPosition() / theme.scale(1));
    }

    @Override
    public void initWidgets() {
        WHorizontalList state = add(theme.horizontalList()).expandX().widget();
        stateLabel = state.add(theme.label(module.isActive() ? "ACTIVE" : "INACTIVE", true)).widget();
        active = state.add(theme.checkbox(module.isActive())).widget();
        active.action = () -> { if (module.isActive() != active.checked) module.toggle(); };
        addOwnership(state);

        add(theme.label(module.description, getWindowWidth() / 2.0));

        if (module.addon != null && module.addon != MonocleClient.ADDON) {
            WHorizontalList addon = add(theme.horizontalList()).expandX().widget();
            addon.add(theme.label("From: ").color(theme.textSecondaryColor())).widget();
            addon.add(theme.label(module.addon.name).color(module.addon.color)).widget();
        }

        // Operational status and custom controls belong before configuration.
        WWidget widget = module.getWidget(theme);

        if (widget != null) {
            add(theme.horizontalSeparator()).expandX();
            Cell<WWidget> cell = add(widget);
            if (widget instanceof WContainer) cell.expandX();
        }

        if (!module.settings.groups.isEmpty()) {
            add(theme.horizontalSeparator("Settings")).expandX();
            WHorizontalList find = add(theme.horizontalList()).expandX().widget();
            searchBox = find.add(theme.textBox(search)).minWidth(190).expandX().widget();
            searchBox.tooltip = "Search setting names, descriptions, and section names. Ctrl/Cmd+F focuses this field.";
            searchStatus = find.add(theme.label("", 120).color(theme.textSecondaryColor())).widget();
            searchBox.action = () -> { search = searchBox.get(); rebuildSettings(); };
            settingsContainer = add(theme.verticalList()).expandX().widget();
            rebuildSettings();
        }

        // Bind
        WSection section = add(theme.section("Bind", true)).expandX().widget();

        // Keybind
        WHorizontalList bind = section.add(theme.horizontalList()).expandX().widget();

        bind.add(theme.label("Bind: "));
        keybind = bind.add(theme.keybind(module.keybind)).expandX().widget();
        keybind.actionOnSet = () -> Modules.get().setModuleToBind(module);

        WButton reset = bind.add(theme.button(GuiRenderer.RESET)).expandCellX().right().widget();
        reset.action = keybind::resetBind;
        reset.tooltip = "Reset";

        // Toggle on bind release
        WHorizontalList tobr = section.add(theme.horizontalList()).widget();

        tobr.add(theme.label("Toggle on bind release: "));
        WCheckbox tobrC = tobr.add(theme.checkbox(module.toggleOnBindRelease)).widget();
        tobrC.action = () -> module.toggleOnBindRelease = tobrC.checked;

        // Chat feedback
        WHorizontalList cf = section.add(theme.horizontalList()).widget();

        cf.add(theme.label("Chat Feedback: "));
        WCheckbox cfC = cf.add(theme.checkbox(module.chatFeedback)).widget();
        cfC.action = () -> module.chatFeedback = cfC.checked;

        add(theme.horizontalSeparator()).expandX();

        // Bottom
        WHorizontalList bottom = add(theme.horizontalList()).expandX().widget();

        // Config sharing
        WHorizontalList sharing = bottom.add(theme.horizontalList()).expandCellX().right().widget();
        WButton copy = sharing.add(theme.button(GuiRenderer.COPY)).widget();
        copy.action = () -> {
            if (toClipboard()) {
                OkPrompt.create()
                    .title("Module copied!")
                    .message("The settings for this module are now in your clipboard.")
                    .message("You can also copy settings using Ctrl+C.")
                    .message("Settings can be imported using Ctrl+V or the paste button.")
                    .id("config-sharing-guide")
                    .show();
            }
        };
        copy.tooltip = "Copy config";

        WButton paste = sharing.add(theme.button(GuiRenderer.PASTE)).widget();
        paste.action = this::fromClipboard;
        paste.tooltip = "Paste config";
    }

    private void rebuildSettings() {
        if (settingsContainer == null) return;
        settingsContainer.clear();
        int matches = ModuleSearch.count(module.settings, search);
        searchStatus.set(matches + (matches == 1 ? " setting" : " settings"));
        if (matches == 0) settingsContainer.add(theme.label("No settings match this search.").color(theme.textSecondaryColor()));
        else settingsContainer.add(theme.settings(module.settings, search)).expandX();
    }

    private void addOwnership(WHorizontalList state) {
        Bots bots = Bots.get();
        BotScheduler.TaskView task = null;
        try { if (bots.tasks().workerBusy()) task = bots.tasks().list().stream().filter(view -> !view.history()).findFirst().orElse(null); }
        catch (RuntimeException ignored) {}
        String owner = task != null ? "MOVEMENT + INVENTORY · " + task.workflowName() + " · " + task.status()
            : bots.crew.assigned() ? "MOVEMENT + INVENTORY · CREW JOB · " + bots.crew.localStatus()
            : BotProfiles.leased() ? "CONFIG · JOB PROFILE OVERLAY" : "CONFIG · PERSONAL";
        state.add(theme.label(owner, 300).color(theme.textSecondaryColor())).expandCellX().right();
        if (task != null) {
            BotScheduler.TaskView selected = task;
            state.add(theme.button("Inspect job")).widget().action = () -> mc.gui.setScreen(new BotTaskScreen(theme, bots, selected.id()));
        } else if (bots.crew.assigned()) {
            state.add(theme.button("Open Workers")).widget().action = () ->
                dev.monocle.client.gui.tabs.Tabs.get(dev.monocle.client.gui.tabs.builtin.BotsTab.class).openScreen(theme);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !Modules.get().isBinding();
    }

    @Override public boolean keyPressed(@NonNull KeyEvent key) {
        boolean control = MacosUtil.IS_MACOS ? (key.modifiers() & MOD_SUPER) != 0 : (key.modifiers() & MOD_CONTROL) != 0;
        if (control && key.key() == KEY_F && searchBox != null) {
            searchBox.setFocused(true);
            searchBox.setCursorMax();
            return true;
        }
        return super.keyPressed(key);
    }

    @Override
    public void tick() {
        super.tick();

        module.settings.tick(settingsContainer, theme, search);
    }

    @EventHandler
    private void onModuleBindChanged(ModuleBindChangedEvent event) {
        keybind.reset();
    }

    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        this.active.checked = module.isActive();
        stateLabel.set(module.isActive() ? "ACTIVE" : "INACTIVE");
    }

    @Override
    public boolean toClipboard() {
        CompoundTag tag = new CompoundTag();

        tag.putString("name", module.name);

        CompoundTag settingsTag = module.settings.toTag();
        if (!settingsTag.isEmpty()) tag.put("settings", settingsTag);

        return NbtUtils.toClipboard(tag);
    }

    @Override
    public boolean fromClipboard() {
        CompoundTag tag = NbtUtils.fromClipboard();
        if (tag == null) return false;
        if (!tag.getStringOr("name", "").equals(module.name)) return false;

        Optional<CompoundTag> settings = tag.getCompound("settings");

        if (settings.isPresent()) module.settings.fromTag(settings.get());
        else module.settings.reset();

        if (parent instanceof WidgetScreen p) p.reload();
        reload();

        return true;
    }
}
