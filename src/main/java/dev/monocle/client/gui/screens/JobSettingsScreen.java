package dev.monocle.client.gui.screens;

import com.google.gson.JsonObject;
import dev.monocle.coordinator.JobSettingControls;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.input.WDoubleEdit;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.utils.Utils;

import java.util.ArrayList;
import java.util.UUID;

/** A draft survives receipt updates; previewed changes go through the existing host scheduler. */
public final class JobSettingsScreen extends WindowScreen {
    private record Target(UUID id, String label) { @Override public String toString() { return label; } }
    private enum Activation { On, Off }
    private final Bots bots;
    private final UUID task;
    private final java.util.List<Target> targets = new ArrayList<>();
    private WDropdown<Target> target;
    private WDropdown<JobSettingControls.Control> control;
    private WDropdown<Activation> activation;
    private WDoubleEdit value;
    private WVerticalList valuePanel;
    private WLabel preview, receipts;
    private WButton apply;
    private JsonObject draft;
    private UUID draftWorker;
    private double width;
    private int ticks;

    public JobSettingsScreen(GuiTheme theme, Bots bots, UUID task) {
        super(theme, "Edit live job settings"); this.bots = bots; this.task = task;
    }
    @Override public void initWidgets() {
        width = Math.clamp(Utils.getWindowWidth() / theme.scale(1) - 100, 330, 660);
        add(theme.label("Changes apply to this job only. Values below are proposed values, not live worker readings. Other settings and future-job defaults stay unchanged.", width)).expandX();
        try {
            JsonObject config = bots.tasks().configuration(task);
            targets.clear(); targets.add(new Target(null, "All unfinished workers in this job"));
            for (String id : config.getAsJsonObject("updates").keySet()) {
                UUID worker = UUID.fromString(id);
                String name = bots.allMembers().stream().filter(m -> m.id().equals(worker)).map(m -> m.name()).findFirst().orElse(id);
                targets.add(new Target(worker, name));
            }
            add(theme.label("Apply to"));
            target = add(theme.dropdown(targets.toArray(Target[]::new), targets.getFirst())).expandX().widget();
            target.action = this::invalidatePreview;
            add(theme.label("Control"));
            control = add(theme.dropdown(JobSettingControls.ALL.toArray(JobSettingControls.Control[]::new), JobSettingControls.ALL.getFirst())).expandX().widget();
            add(theme.label("Proposed module activation"));
            activation = add(theme.dropdown(Activation.On)).expandX().widget(); activation.action = this::invalidatePreview;
            valuePanel = add(theme.verticalList()).expandX().widget();
            preview = add(theme.label("Choose the target and values, then preview the change.", width)).expandX().widget();
            add(theme.button("Preview change")).expandX().widget().action = () -> {
                try {
                    JsonObject request = new JsonObject(); request.addProperty("control", control.get().id());
                    request.addProperty("active", activation.get() == Activation.On);
                    if (value != null) request.addProperty("value", value.get());
                    JsonObject proposed = JobSettingControls.preview(request);
                    draft = proposed.getAsJsonObject("modules"); draftWorker = target.get().id();
                    preview.set("Target: " + target.get() + "\n" + proposed.get("description").getAsString() + "\n" + proposed.get("scope").getAsString());
                    apply.set("Apply previewed change");
                } catch (RuntimeException e) { invalidatePreview(); preview.set("Cannot preview: " + e.getMessage()); }
            };
            apply = add(theme.button("Preview required before applying")).expandX().widget();
            apply.action = () -> {
                if (draft == null) { preview.set("Preview the current target and values first."); return; }
                try {
                    bots.tasks().configure(task, draftWorker, draft);
                    invalidatePreview(); preview.set("Request saved. Wait for the worker acknowledgement below before assuming it has taken effect.");
                } catch (RuntimeException e) { invalidatePreview(); preview.set("Request not confirmed: " + e.getMessage() + ". Check acknowledgements before retrying."); }
                refreshReceipts();
            };
            receipts = add(theme.label("", width)).expandX().widget();
            control.action = () -> { invalidatePreview(); showValue(); };
            showValue(); refreshReceipts();
        } catch (RuntimeException e) { add(theme.label("Settings unavailable: " + e.getMessage(), width)); }
        add(theme.button("Back to task")).expandX().widget().action = this::onClose;
    }
    private void invalidatePreview() {
        draft = null; draftWorker = null;
        if (apply != null) apply.set("Preview required before applying");
        if (preview != null) preview.set("Draft changed. Preview again before applying.");
    }
    private void showValue() {
        valuePanel.clear(); value = null;
        var setting = control.get();
        valuePanel.add(theme.label(setting.help(), width)).expandX();
        if (setting.numeric()) {
            valuePanel.add(theme.label("Proposed value (not read from worker)"));
            value = valuePanel.add(theme.doubleEdit(setting.example(), setting.min(), setting.max(), setting.min(), setting.max(), setting.step() == 1 ? 0 : 2, false)).expandX().widget();
            value.action = this::invalidatePreview;
        }
    }
    private void refreshReceipts() {
        if (receipts == null) return;
        try {
            StringBuilder text = new StringBuilder("Latest configuration acknowledgements");
            var updates = bots.tasks().configuration(task).getAsJsonObject("updates");
            for (Target worker : targets) {
                if (worker.id() == null || !updates.has(worker.id().toString())) continue;
                JsonObject update = updates.getAsJsonObject(worker.id().toString());
                text.append("\n").append(worker.label()).append(": ").append(update.get("status").getAsString())
                    .append(" · requested ").append(update.get("requestedRevision")).append(" / acknowledged ").append(update.get("acknowledgedRevision"));
                if (!update.get("error").getAsString().isEmpty()) text.append("\n").append(update.get("error").getAsString());
            }
            receipts.set(text.toString());
        } catch (RuntimeException e) { receipts.set("Acknowledgements unavailable: " + e.getMessage()); }
    }
    @Override public void tick() { super.tick(); if (++ticks % 20 == 0) refreshReceipts(); }
}
