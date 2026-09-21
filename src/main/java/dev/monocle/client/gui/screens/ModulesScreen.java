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
import dev.monocle.client.gui.widgets.containers.WContainer;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.containers.WWindow;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.systems.modules.Category;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.utils.misc.NbtUtils;
import dev.monocle.client.utils.render.DisplayItemUtils;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

import static dev.monocle.client.utils.Utils.getWindowHeight;
import static dev.monocle.client.utils.Utils.getWindowWidth;
import static com.mojang.blaze3d.platform.InputConstants.*;

public class ModulesScreen extends TabScreen {
    private enum Filter { All, Active, Favorites }
    private static String rememberedQuery = "";
    private static Filter rememberedFilter = Filter.All;
    private static final java.util.Map<String, Double> rememberedScroll = new java.util.HashMap<>();
    private WCategoryController controller;
    private WWindow searchWindow;
    private WTextBox searchTextBox;
    private WVerticalList searchResults;
    private String searchShape = "";
    private int ticks;
    private dev.monocle.client.gui.widgets.WLabel ownership;

    public ModulesScreen(GuiTheme theme) {
        super(theme, Tabs.get().getFirst());
    }

    @Override
    public void initWidgets() {
        controller = add(new WCategoryController()).widget();

        // Help
        if (theme.modulesHelpText()) {
            WVerticalList help = add(theme.verticalList()).pad(4).bottom().widget();
            help.add(theme.label("Left click - Toggle module"));
            help.add(theme.label("Right click - Open module settings"));
        }
    }

    @Override
    protected void init() {
        super.init();
        controller.refresh();
        for (WWindow window : controller.windows) window.view.restoreScroll(theme.scale(rememberedScroll.getOrDefault(window.id, 0.0)));
    }

    @Override protected void onClosed() {
        super.onClosed();
        if (controller != null) for (WWindow window : controller.windows)
            rememberedScroll.put(window.id, window.view.scrollPosition() / theme.scale(1));
    }

    // Category

    protected WWindow createCategory(WContainer c, Category category, List<Module> moduleList) {
        WWindow w = theme.window(category.name);
        w.id = category.name;
        w.padding = 0;
        w.spacing = 0;

        if (theme.categoryIcons()) {
            w.beforeHeaderInit = wContainer -> wContainer.add(theme.item(category.icon.get())).pad(2);
        }

        c.add(w);
        w.view.scrollOnlyWhenMouseOver = true;
        w.view.hasScrollBar = false;
        w.view.spacing = 0;

        for (Module module : moduleList) {
            w.add(theme.module(module)).expandX();
        }

        return w;
    }

    // Search

    protected void createSearchW(WContainer w, String text) {
        if (text.isBlank() && rememberedFilter == Filter.All) {
            w.add(theme.label("Search names, descriptions\nand settings · Ctrl/Cmd+F").color(theme.textSecondaryColor()));
            return;
        }
        List<Module> results = Modules.get().getAll().stream()
            .filter(module -> !Config.get().hiddenModules.get().contains(module))
            .filter(module -> rememberedFilter != Filter.Active || module.isActive())
            .filter(module -> rememberedFilter != Filter.Favorites || module.favorite)
            .filter(module -> ModuleSearch.matches(text, searchDocument(module)))
            .sorted(java.util.Comparator.comparing(module -> module.title, String.CASE_INSENSITIVE_ORDER))
            .toList();
        int limit = Math.max(1, Config.get().moduleSearchCount.get());
        w.add(theme.label(results.size() + " matches" + (results.size() > limit ? " · showing " + limit : ""))
            .color(theme.textSecondaryColor()));
        for (Module module : results.stream().limit(limit).toList()) w.add(theme.module(module)).expandX();
    }

    private String searchDocument(Module module) {
        StringBuilder text = new StringBuilder(module.title).append(' ').append(module.name).append(' ').append(module.description);
        if (Config.get().moduleAliases.get()) for (String alias : module.aliases) text.append(' ').append(alias);
        for (var group : module.settings) for (var setting : group) text.append(' ').append(setting.title).append(' ').append(setting.description);
        return text.toString();
    }

    protected WWindow createSearch(WContainer c) {
        WWindow w = theme.window("Find modules");
        w.id = "search";
        searchWindow = w;

        if (theme.categoryIcons()) {
            w.beforeHeaderInit = wContainer -> wContainer.add(theme.item(DisplayItemUtils.toStack(Items.COMPASS))).pad(2);
        }

        c.add(w);
        w.view.scrollOnlyWhenMouseOver = true;
        w.view.hasScrollBar = false;
        w.view.maxHeight -= 20;

        var filter = w.add(theme.dropdown(rememberedFilter)).expandX().widget();
        filter.action = () -> { rememberedFilter = filter.get(); refreshSearch(); };

        WTextBox text = w.add(theme.textBox(rememberedQuery)).minWidth(190).expandX().widget();
        text.setFocused(true);
        searchTextBox = text;
        text.action = () -> {
            rememberedQuery = text.get();
            refreshSearch();
        };

        searchResults = w.add(theme.verticalList()).expandX().widget();
        refreshSearch();
        w.add(theme.horizontalSeparator()).expandX();
        ownership = w.add(theme.label("", 210).color(theme.textSecondaryColor())).expandX().widget();
        refreshOwnership();
        w.add(theme.button("Workers & job controls")).expandX().widget().action =
            () -> Tabs.get(dev.monocle.client.gui.tabs.builtin.BotsTab.class).openScreen(theme);

        return w;
    }

    private void refreshSearch() {
        if (searchResults == null) return;
        searchResults.clear();
        createSearchW(searchResults, rememberedQuery);
    }

    private void refreshOwnership() {
        var bots = dev.monocle.client.systems.bots.Bots.get();
        String controls = bots.tasks().workerBusy() ? "Worker task owns controls"
            : bots.crew.assigned() ? "Highway crew assigned · inspect Workers" : "No worker task owns controls";
        ownership.set(controls + (dev.monocle.client.systems.bots.BotProfiles.leased() ? "\nJob profile overlay active" : "\nNo workflow profile overlay"));
    }

    @Override public void tick() {
        super.tick();
        if (++ticks % 20 != 0) return;
        refreshOwnership();
        String shape = Modules.get().getAll().stream().filter(m -> m.isActive() || m.favorite)
            .map(m -> m.name + m.isActive() + m.favorite).sorted().toList().toString();
        if (!shape.equals(searchShape)) { searchShape = shape; refreshSearch(); }
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent value) {
        if (locked) return false;

        boolean cntrl = MacosUtil.IS_MACOS ? value.modifiers() == MOD_SUPER : value.modifiers() == MOD_CONTROL;

        if (cntrl && value.key() == KEY_F) {
            if (searchWindow != null) searchWindow.setExpanded(true);
            if (searchTextBox != null) {
                searchTextBox.setFocused(true);
                searchTextBox.setCursorMax();
            }

            return true;
        }

        return super.keyPressed(value);
    }

    // Favorites

    protected Cell<WWindow> createFavorites(WContainer c) {
        boolean hasFavorites = Modules.get().getAll().stream().anyMatch(module -> module.favorite);
        if (!hasFavorites) return null;

        WWindow w = theme.window("Favorites");
        w.id = "favorites";
        w.padding = 0;
        w.spacing = 0;

        if (theme.categoryIcons()) {
            w.beforeHeaderInit = wContainer -> wContainer.add(theme.item(DisplayItemUtils.toStack(Items.NETHER_STAR))).pad(2);
        }

        Cell<WWindow> cell = c.add(w);
        w.view.scrollOnlyWhenMouseOver = true;
        w.view.hasScrollBar = false;
        w.view.spacing = 0;

        createFavoritesW(w);
        return cell;
    }

    protected boolean createFavoritesW(WWindow w) {
        List<Module> modules = new ArrayList<>();

        for (Module module : Modules.get().getAll()) {
            if (module.favorite) {
                modules.add(module);
            }
        }

        modules.sort((o1, o2) -> String.CASE_INSENSITIVE_ORDER.compare(o1.name, o2.name));

        for (Module module : modules) {
            w.add(theme.module(module)).expandX();
        }

        return !modules.isEmpty();
    }

    @Override
    public boolean toClipboard() {
        return NbtUtils.toClipboard(Modules.get());
    }

    @Override
    public boolean fromClipboard() {
        return NbtUtils.fromClipboard(Modules.get());
    }

    @Override
    public void reload() {
    }

    // Stuff

    protected class WCategoryController extends WContainer {
        public final List<WWindow> windows = new ArrayList<>();
        private Cell<WWindow> favorites;

        @Override
        public void init() {
            List<Module> moduleList = new ArrayList<>();
            for (Category category : Modules.loopCategories()) {
                for (Module module : Modules.get().getGroup(category)) {
                    if (!Config.get().hiddenModules.get().contains(module)) {
                        moduleList.add(module);
                    }
                }

                // Ensure empty categories are not shown
                if (!moduleList.isEmpty()) {
                    windows.add(createCategory(this, category, moduleList));
                    moduleList.clear();
                }
            }

            windows.add(createSearch(this));

            refresh();
        }

        protected void refresh() {
            if (favorites == null) {
                favorites = createFavorites(this);
                if (favorites != null) windows.add(favorites.widget());
            } else {
                favorites.widget().clear();

                if (!createFavoritesW(favorites.widget())) {
                    remove(favorites);
                    windows.remove(favorites.widget());
                    favorites = null;
                }
            }
        }

        @Override
        protected void onCalculateWidgetPositions() {
            double pad = theme.scale(4);
            double h = theme.scale(40);

            double x = this.x + pad;
            double y = Math.max(this.y, navigation == null ? theme.scale(40) : navigation.height + theme.scale(8));

            for (Cell<?> cell : cells) {
                double windowWidth = getWindowWidth();
                double windowHeight = getWindowHeight();

                if (x + cell.width > windowWidth) {
                    x = this.x + pad;
                    y += h;
                }

                if (x > windowWidth) {
                    x = windowWidth / 2.0 - cell.width / 2.0;
                    if (x < 0) x = 0;
                }
                if (y > windowHeight) {
                    y = windowHeight / 2.0 - cell.height / 2.0;
                    if (y < 0) y = 0;
                }

                cell.x = x;
                cell.y = y;

                cell.width = cell.widget().width;
                cell.height = cell.widget().height;

                cell.alignWidget();

                x += cell.width + pad;
            }
        }
    }
}
