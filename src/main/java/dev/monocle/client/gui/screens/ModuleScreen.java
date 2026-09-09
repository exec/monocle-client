/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.monocle.ActiveModulesChangedEvent;
import dev.monocle.client.events.monocle.ModuleBindChangedEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.utils.Cell;
import dev.monocle.client.gui.widgets.WKeybind;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WContainer;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WSection;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.gui.widgets.pressable.WCheckbox;
import dev.monocle.client.gui.widgets.pressable.WFavorite;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.utils.misc.NbtUtils;
import dev.monocle.client.utils.render.prompts.OkPrompt;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.CompoundTag;

import java.util.Optional;

import static dev.monocle.client.utils.Utils.getWindowWidth;

public class ModuleScreen extends WindowScreen {
    private final Module module;

    private WContainer settingsContainer;
    private WKeybind keybind;
    private WCheckbox active;

    public ModuleScreen(GuiTheme theme, Module module) {
        super(theme, theme.favorite(module.favorite), module.title);
        ((WFavorite) window.icon).action = () -> module.favorite = ((WFavorite) window.icon).checked;

        this.module = module;
    }

    @Override
    public void initWidgets() {
        // Description
        add(theme.label(module.description, getWindowWidth() / 2.0));

        if (module.addon != null && module.addon != MonocleClient.ADDON) {
            WHorizontalList addon = add(theme.horizontalList()).expandX().widget();
            addon.add(theme.label("From: ").color(theme.textSecondaryColor())).widget();
            addon.add(theme.label(module.addon.name).color(module.addon.color)).widget();
        }

        // Settings
        if (!module.settings.groups.isEmpty()) {
            settingsContainer = add(theme.verticalList()).expandX().widget();
            settingsContainer.add(theme.settings(module.settings)).expandX();
        }

        // Custom widget
        WWidget widget = module.getWidget(theme);

        if (widget != null) {
            add(theme.horizontalSeparator()).expandX();
            Cell<WWidget> cell = add(widget);
            if (widget instanceof WContainer) cell.expandX();
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

        // Active
        bottom.add(theme.label("Active: "));
        active = bottom.add(theme.checkbox(module.isActive())).expandCellX().widget();
        active.action = () -> {
            if (module.isActive() != active.checked) module.toggle();
        };

        // Config sharing
        WHorizontalList sharing = bottom.add(theme.horizontalList()).right().widget();
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

    @Override
    public boolean shouldCloseOnEsc() {
        return !Modules.get().isBinding();
    }

    @Override
    public void tick() {
        super.tick();

        module.settings.tick(settingsContainer, theme);
    }

    @EventHandler
    private void onModuleBindChanged(ModuleBindChangedEvent event) {
        keybind.reset();
    }

    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        this.active.checked = module.isActive();
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
