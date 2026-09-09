package dev.monocle.client.gui.screens;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WContainer;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.settings.Settings;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.render.color.Color;
import net.minecraft.util.StringUtil;

import static dev.monocle.client.MonocleClient.mc;

public class HighwayBuilderScreen extends WindowScreen {
    private final HighwayBuilder builder;
    private final Settings buildSettings;
    private WLabel status, plan, stats, readiness, supplies;
    private WContainer setup;
    private WHorizontalList actions;
    private WButton preview;
    private boolean shownJob, shownPaused;

    public HighwayBuilderScreen(GuiTheme theme, HighwayBuilder builder) {
        super(theme, "Highway Builder");
        this.builder = builder;
        this.buildSettings = builder.buildSettings();
    }

    @Override
    protected void init() {
        boolean returning = !firstInit;
        super.init();
        if (returning) {
            rebuildControls();
            refreshLabels();
        }
    }

    @Override
    public void initWidgets() {
        status = add(theme.label(builder.getStatus(), true, 580)).expandX().widget();
        actions = add(theme.horizontalList()).expandX().widget();
        plan = add(theme.label(builder.getPlanSummary(), 580)).expandX().widget();
        stats = add(theme.label("", 580)).expandX().widget();
        add(theme.horizontalSeparator()).expandX();

        WHorizontalList body = add(theme.horizontalList()).expandX().widget();
        setup = body.add(theme.verticalList()).top().expandX().widget();
        WVerticalList details = body.add(theme.verticalList()).top().widget();

        details.add(theme.label("Cross-section"));
        details.add(new CrossSection()).expandX();
        details.add(theme.label("Blue: blocks placed\nRed: space cleared\nGray: existing space / floor\nSchematic; preview shows exact layout.", 205)
            .color(theme.textSecondaryColor()));
        details.add(theme.horizontalSeparator("Readiness")).expandX();
        readiness = details.add(theme.label(builder.getReadiness(), 205)).widget();
        details.add(theme.horizontalSeparator("Supplies")).expandX();
        supplies = details.add(theme.label(builder.getSuppliesSummary(), 205)).widget();

        add(theme.horizontalSeparator()).expandX();
        WHorizontalList bottom = add(theme.horizontalList()).expandX().widget();
        preview = bottom.add(theme.button("Show world preview")).widget();
        preview.action = () -> {
            builder.setPreview(!builder.isPreviewEnabled());
            builder.updatePreview();
        };
        preview.tooltip = "Show the planned work in the world before starting. Close this window to inspect it.";

        WButton advanced = bottom.add(theme.button("Advanced & keybind")).expandCellX().right().widget();
        advanced.action = () -> {
            if (builder.hasJob() && !builder.isJobPaused()) builder.pauseJob();
            mc.gui.setScreen(new ModuleScreen(theme, builder));
        };
        advanced.tooltip = "Open all settings and the module keybind. Pauses an active build before editing.";

        rebuildControls();
        refreshLabels();
    }

    private void rebuildControls() {
        shownJob = builder.hasJob();
        shownPaused = builder.isJobPaused();
        actions.clear();

        WButton primary = actions.add(theme.button(!shownJob ? "Start" : shownPaused ? "Resume" : "Pause"))
            .expandX().widget();
        primary.action = () -> {
            if (!builder.hasJob()) builder.startJob();
            else if (builder.isJobPaused()) builder.resumeJob();
            else builder.pauseJob();
        };
        enterAction = () -> {
            if (!setup.isFocused()) primary.action.run();
        };
        primary.tooltip = "Enter: start, pause or resume when no field is selected. Pause releases controls and keeps job progress.";

        if (shownJob) {
            WButton stop = actions.add(theme.button("Stop")).widget();
            stop.action = builder::stopJob;
            stop.tooltip = "End this job. Its final statistics stay visible.";
        } else {
            WButton test = actions.add(theme.button("Test 20 blocks")).widget();
            test.action = () -> {
                if (!builder.hasJob()) builder.startTestRun();
            };
            test.tooltip = "Run this setup for 20 blocks, then stop automatically.";
        }

        setup.clear();
        if (!shownJob || shownPaused) setup.add(theme.settings(buildSettings)).expandX();
        else setup.add(theme.label("Pause to edit setup.\nFinish supply recovery before changing the highway shape.\nYou can look around while building.", 310));
    }

    private void refreshLabels() {
        status.set(builder.getHudStatus() + " · " + builder.getPavingRate());
        status.color(builder.isHudHealthy() ? new Color(100, 220, 145) : theme.textColor());
        plan.set(builder.getPlanSummary());
        stats.set(StringUtil.stripColor(builder.getStatsText().getString()).replace("\n", " · "));
        readiness.set(builder.getReadiness());
        supplies.set(builder.getSuppliesSummary());
        preview.set(builder.isPreviewEnabled() ? "Hide world preview" : "Show world preview");
    }

    @Override
    public void tick() {
        super.tick();
        if (shownJob != builder.hasJob() || shownPaused != builder.isJobPaused()) rebuildControls();
        if (!shownJob || shownPaused) buildSettings.tick(setup, theme);
        builder.updatePreview();
        refreshLabels();
    }

    private class CrossSection extends WWidget {
        private static final Color BUILD = new Color(78, 143, 244);
        private static final Color CLEAR = new Color(210, 80, 77, 105);
        private static final Color EXISTING = new Color(109, 119, 133, 80);

        @Override
        protected void onCalculateSize() {
            width = theme.scale(205);
            height = theme.scale(112);
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            int roadWidth = builder.getPreviewWidth();
            int clearance = builder.getPreviewHeight();
            double cell = theme.scale(14);
            double left = x + (width - (roadWidth + 2) * cell) / 2;
            double top = y + (height - (clearance + 1) * cell) / 2;

            for (int column = 0; column < roadWidth; column++) {
                for (int row = 0; row < clearance; row++) {
                    block(renderer, left + (column + 1) * cell, top + row * cell, cell,
                        builder.doesDig() ? CLEAR : EXISTING);
                }
                block(renderer, left + (column + 1) * cell, top + clearance * cell, cell,
                    builder.hasPreviewFloor() ? BUILD : EXISTING);
            }

            if (builder.hasPreviewRailings()) {
                for (int column : new int[] { 0, roadWidth + 1 }) {
                    block(renderer, left + column * cell, top + (clearance - 1) * cell, cell, BUILD);
                    if (builder.hasPreviewSupports())
                        block(renderer, left + column * cell, top + clearance * cell, cell, BUILD);
                }
            }
        }

        private void block(GuiRenderer renderer, double x, double y, double size, Color color) {
            renderer.quad(x, y, size - theme.scale(1), size - theme.scale(1), color);
        }
    }
}
