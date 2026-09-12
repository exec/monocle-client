package dev.monocle.client.gui.screens;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.containers.WContainer;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.containers.WSection;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.settings.Settings;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.render.color.Color;
import net.minecraft.util.StringUtil;

import static dev.monocle.client.MonocleClient.mc;

public class HighwayBuilderScreen extends WindowScreen {
    private final HighwayBuilder builder;
    private final Settings buildSettings;
    private WLabel status, plan, stats, readiness, supplies, timings;
    private int timingTicks;
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

        WSection timingSection = add(theme.section("Timing breakdown (local job)", false)).expandX().widget();
        timingSection.add(theme.label("Controller phase time, not CPU time. Background mining/paving can continue during a verification or entity wait. Same-job crew handoffs retain totals; a new job resets them.", 580));
        timings = timingSection.add(theme.label(builder.getTimingSummary(), 580)).expandX().widget();
        timingSection.add(theme.button("Copy timings")).widget().action = () -> mc.keyboardHandler.setClipboard(
            "Monocle " + dev.monocle.client.MonocleClient.VERSION + "\n" + builder.getTimingSummary());

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
            mc.gui.setScreen(new ModuleScreen(theme, builder));
        };
        advanced.tooltip = "Inspect all settings and the module keybind without pausing. Changing the build layout still pauses for review.";

        rebuildControls();
        refreshLabels();
    }

    private void rebuildControls() {
        shownJob = builder.hasJob();
        shownPaused = builder.isJobPaused();
        actions.clear();

        if (Bots.get().crew.localAssigned()) {
            WButton manage = actions.add(theme.button("Manage crew in Bots")).expandX().widget();
            manage.action = () -> dev.monocle.client.gui.tabs.Tabs.get().stream().filter(tab -> tab.name.equals("Bots")).findFirst().ifPresent(tab -> tab.openScreen(theme));
            manage.tooltip = "Crew lifecycle is controlled from the host's Bots tab. Emergency module disable remains available.";
            enterAction = manage.action;
            setup.clear();
            setup.add(theme.label("This highway belongs to a bot crew.\nUse the host's Bots tab to inspect, pause, resume or end the job.", 310));
            return;
        }

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
        if (++timingTicks % 20 == 0) timings.set(builder.getTimingSummary());
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
