/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens;

import com.mojang.blaze3d.platform.MacosUtil;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.tabs.TabScreen;
import dev.monocle.client.gui.tabs.Tabs;
import dev.monocle.client.gui.utils.Cell;
import dev.monocle.client.gui.utils.WorkspaceLayout;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.WContainer;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.containers.WWindow;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.systems.modules.Category;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.utils.misc.NbtUtils;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.mojang.blaze3d.platform.InputConstants.*;
import static dev.monocle.client.utils.Utils.getWindowHeight;
import static dev.monocle.client.utils.Utils.getWindowWidth;

/** The right-shift module workspace: fixed navigation and a responsive module library. */
public class ModulesScreen extends TabScreen {
    private enum Filter { All, Active, Favorites }

    private static String rememberedQuery = "", rememberedCategory = "";
    private static Filter rememberedFilter = Filter.All;
    private static double controlScroll, libraryScroll;

    private WWindow controls, library;
    private WVerticalList libraryBody;
    private WTextBox searchTextBox;
    private WLabel stats, ownership;
    private String moduleShape = "";
    private int ticks;

    public ModulesScreen(GuiTheme theme) {
        super(theme, Tabs.get().getFirst());
    }

    @Override public void initWidgets() {
        add(new WModuleWorkspace());
    }

    @Override protected void init() {
        super.init();
        controls.view.restoreScroll(theme.scale(controlScroll));
        library.view.restoreScroll(theme.scale(libraryScroll));
    }

    @Override protected void onClosed() {
        super.onClosed();
        if (controls != null) controlScroll = controls.view.scrollPosition() / theme.scale(1);
        if (library != null) libraryScroll = library.view.scrollPosition() / theme.scale(1);
    }

    private void fillControls() {
        controls.clear();
        stats = controls.add(theme.label("").color(theme.textSecondaryColor())).expandX().widget();
        refreshStats();

        var filter = controls.add(theme.dropdown(rememberedFilter)).expandX().widget();
        filter.tooltip = "Show every module, active modules, or favorites.";
        filter.action = () -> { rememberedFilter = filter.get(); refreshLibrary(); refreshStats(); };

        searchTextBox = controls.add(theme.textBox(rememberedQuery, "Search modules…")).expandX().widget();
        searchTextBox.tooltip = "Search names, descriptions and settings · Ctrl/Cmd+F";
        searchTextBox.action = () -> { rememberedQuery = searchTextBox.get(); refreshLibrary(); refreshStats(); };

        controls.add(theme.horizontalSeparator("Browse")).expandX();
        addCategoryButton("", "All modules");
        for (Category category : Modules.loopCategories()) {
            boolean visible = Modules.get().getGroup(category).stream().anyMatch(module -> !Config.get().hiddenModules.get().contains(module));
            if (visible) addCategoryButton(category.name, category.name);
        }

        controls.add(theme.horizontalSeparator("Control")).expandX();
        ownership = controls.add(theme.label("", 220).color(theme.textSecondaryColor())).expandX().widget();
        refreshOwnership();
        controls.add(theme.button("Open Workers & jobs")).expandX().widget().action =
            () -> Tabs.get(dev.monocle.client.gui.tabs.builtin.BotsTab.class).openScreen(theme);
        controls.add(theme.label("Left click toggles · Right click configures", 220).color(theme.textSecondaryColor())).expandX();
    }

    private void addCategoryButton(String key, String title) {
        String label = rememberedCategory.equals(key) ? "◆  " + title : "   " + title;
        var button = controls.add(theme.button(label)).expandX().widget();
        button.action = () -> {
            rememberedCategory = key;
            fillControls();
            refreshLibrary();
        };
    }

    private void refreshLibrary() {
        if (libraryBody == null) return;
        libraryBody.clear();
        List<Module> modules = matchingModules();
        String scope = rememberedCategory.isBlank() ? "All modules" : rememberedCategory;
        libraryBody.add(theme.label(scope + "  ·  " + modules.size() + (modules.size() == 1 ? " module" : " modules"), true));
        if (!rememberedQuery.isBlank()) libraryBody.add(theme.label("Results for “" + rememberedQuery.strip() + "”").color(theme.textSecondaryColor()));
        if (modules.isEmpty()) {
            libraryBody.add(theme.horizontalSeparator()).expandX();
            libraryBody.add(theme.label("Nothing matches this view. Try another category or clear the search.", 420).color(theme.textSecondaryColor()));
        } else libraryBody.add(new WModuleGrid(modules)).expandX();
    }

    private List<Module> matchingModules() {
        return Modules.get().getAll().stream()
            .filter(module -> !Config.get().hiddenModules.get().contains(module))
            .filter(module -> rememberedCategory.isBlank() || module.category.name.equals(rememberedCategory))
            .filter(module -> rememberedFilter != Filter.Active || module.isActive())
            .filter(module -> rememberedFilter != Filter.Favorites || module.favorite)
            .filter(module -> ModuleSearch.matches(rememberedQuery, searchDocument(module)))
            .sorted(Comparator.comparing(module -> module.title, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private String searchDocument(Module module) {
        StringBuilder text = new StringBuilder(module.title).append(' ').append(module.name).append(' ').append(module.description);
        if (Config.get().moduleAliases.get()) for (String alias : module.aliases) text.append(' ').append(alias);
        for (var group : module.settings) for (var setting : group) text.append(' ').append(setting.title).append(' ').append(setting.description);
        return text.toString();
    }

    private void refreshStats() {
        if (stats == null) return;
        long active = Modules.get().getAll().stream().filter(Module::isActive).count();
        long favorites = Modules.get().getAll().stream().filter(module -> module.favorite).count();
        stats.set(active + " active  ·  " + favorites + " favorite" + (favorites == 1 ? "" : "s"));
    }

    private void refreshOwnership() {
        if (ownership == null) return;
        var bots = dev.monocle.client.systems.bots.Bots.get();
        String controls = bots.tasks().workerBusy() ? "Worker task owns movement and inventory"
            : bots.crew.assigned() ? "Highway crew owns movement and inventory" : "Personal controls active";
        ownership.set(controls + (dev.monocle.client.systems.bots.BotProfiles.leased() ? "\nJob profile overlay active" : "\nPersonal profile active"));
    }

    @Override public void tick() {
        super.tick();
        if (++ticks % 20 != 0) return;
        refreshOwnership();
        refreshStats();
        String shape = Modules.get().getAll().stream().filter(module -> module.isActive() || module.favorite)
            .map(module -> module.name + module.isActive() + module.favorite).sorted().toList().toString();
        if (!shape.equals(moduleShape)) { moduleShape = shape; refreshLibrary(); }
    }

    @Override public boolean keyPressed(@NonNull KeyEvent value) {
        if (locked) return false;
        boolean control = MacosUtil.IS_MACOS ? (value.modifiers() & MOD_SUPER) != 0 : (value.modifiers() & MOD_CONTROL) != 0;
        if (control && value.key() == KEY_F && searchTextBox != null) {
            searchTextBox.setFocused(true);
            searchTextBox.setCursorMax();
            return true;
        }
        return super.keyPressed(value);
    }

    @Override public boolean toClipboard() { return NbtUtils.toClipboard(Modules.get()); }
    @Override public boolean fromClipboard() { return NbtUtils.fromClipboard(Modules.get()); }
    @Override public void reload() { fillControls(); refreshLibrary(); }

    private final class WModuleWorkspace extends WContainer {
        private Cell<WWindow> controlsCell, libraryCell;

        @Override public void init() {
            controls = theme.window("Module control");
            controls.id = "modules-control";
            controls.layoutLocked = true;
            controls.padding = 6;
            controlsCell = add(controls).expandWidgetX();
            fillControls();

            library = theme.window("Module library");
            library.id = "modules-library";
            library.layoutLocked = true;
            library.padding = 6;
            libraryCell = add(library).expandWidgetX();
            libraryBody = library.add(theme.verticalList()).expandX().widget();
            refreshLibrary();
        }

        @Override public void calculateSize() {
            double top = navigation == null ? theme.scale(40) : navigation.height + theme.scale(8);
            double available = Math.max(theme.scale(180), getWindowHeight() - top - theme.scale(10));
            boolean stacked = WorkspaceLayout.stacked(getWindowWidth() / theme.scale(1), 720);
            controls.view.maxHeight = stacked ? Math.min(theme.scale(280), available * .45) : available - theme.scale(28);
            library.view.maxHeight = stacked ? Math.max(theme.scale(120), available - controls.view.maxHeight - theme.scale(38)) : available - theme.scale(28);
            super.calculateSize();
        }

        @Override protected void onCalculateWidgetPositions() {
            double pad = theme.scale(8), gap = theme.scale(8);
            double top = Math.max(y, navigation == null ? theme.scale(40) : navigation.height + theme.scale(8));
            double total = getWindowWidth();
            boolean stacked = WorkspaceLayout.stacked(total / theme.scale(1), 720);
            if (stacked) {
                place(controlsCell, pad, top, total - pad * 2);
                place(libraryCell, pad, top + controls.layoutHeight() + gap, total - pad * 2);
            } else {
                double rail = Math.clamp(total * .23, theme.scale(220), theme.scale(280));
                place(controlsCell, pad, top, rail);
                place(libraryCell, pad + rail + gap, top, total - rail - gap - pad * 2);
            }
        }

        private void place(Cell<WWindow> cell, double x, double y, double width) {
            cell.x = x;
            cell.y = y;
            cell.width = Math.max(theme.scale(160), width);
            cell.height = cell.widget().height;
            cell.alignWidget();
        }
    }

    private final class WModuleGrid extends WContainer {
        private final List<Module> modules;
        private int columns;
        private double gap, cellWidth;

        private WModuleGrid(List<Module> modules) { this.modules = new ArrayList<>(modules); }

        @Override public void init() {
            for (Module module : modules) add(theme.module(module)).expandWidgetX();
        }

        @Override protected void onCalculateSize() {
            gap = theme.scale(4);
            double total = getWindowWidth();
            double rail = total / theme.scale(1) < 720 ? 0 : Math.clamp(total * .23, theme.scale(220), theme.scale(280)) + theme.scale(8);
            width = Math.max(theme.scale(160), total - rail - theme.scale(32));
            columns = WorkspaceLayout.columns(width, theme.scale(170), gap, 4);
            cellWidth = (width - gap * (columns - 1)) / columns;
            height = 0;
            for (int row = 0; row * columns < cells.size(); row++) {
                double rowHeight = 0;
                for (int column = 0; column < columns && row * columns + column < cells.size(); column++)
                    rowHeight = Math.max(rowHeight, cells.get(row * columns + column).widget().height);
                if (row > 0) height += gap;
                height += rowHeight;
            }
        }

        @Override protected void onCalculateWidgetPositions() {
            double rowY = y;
            for (int row = 0; row * columns < cells.size(); row++) {
                double rowHeight = 0;
                for (int column = 0; column < columns && row * columns + column < cells.size(); column++)
                    rowHeight = Math.max(rowHeight, cells.get(row * columns + column).widget().height);
                for (int column = 0; column < columns && row * columns + column < cells.size(); column++) {
                    Cell<?> cell = cells.get(row * columns + column);
                    cell.x = x + column * (cellWidth + gap);
                    cell.y = rowY;
                    cell.width = cellWidth;
                    cell.height = rowHeight;
                    cell.alignWidget();
                }
                rowY += rowHeight + gap;
            }
        }
    }
}
