package dev.monocle.client.gui.screens;

import com.google.gson.GsonBuilder;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WTable;
import dev.monocle.client.gui.widgets.containers.WSection;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.systems.bots.BotWorkflows;
import dev.monocle.client.systems.bots.BotProfiles;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.render.prompts.YesNoPrompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import static dev.monocle.client.MonocleClient.mc;

/** Edits definition references; running jobs keep their own compiled workflow snapshots. */
public final class WorkflowEditorScreen extends WindowScreen {
    private record Target(String id, String label) { @Override public String toString() { return label; } }
    private final Bots bots;
    private final String id;
    private final List<BotWorkflows.Step> draft = new ArrayList<>();
    private BotWorkflows.Workflow workflow;
    private WTextBox name, folder;
    private WVerticalList steps;
    private WLabel feedback;
    private WorkflowCodeBox code;
    private final Set<String> dependencies = new LinkedHashSet<>(), profiles = new LinkedHashSet<>();
    private double contentWidth;
    private boolean readOnly;
    private boolean discardRequested;

    public WorkflowEditorScreen(GuiTheme theme, Bots bots, String id) {
        super(theme, "Workflow definition"); this.bots = bots; this.id = id;
    }

    @Override public void initWidgets() {
        contentWidth = Math.clamp(Utils.getWindowWidth() / theme.scale(1) - 100, 330, 660);
        try { workflow = bots.workflows().get(id); }
        catch (RuntimeException e) {
            add(theme.label("Workflow unavailable: " + message(e), contentWidth));
            add(theme.button("Back")).widget().action = this::onClose;
            return;
        }
        draft.clear(); draft.addAll(workflow.steps());
        dependencies.clear(); dependencies.addAll(workflow.dependencies());
        profiles.clear(); profiles.addAll(workflow.profiles());
        readOnly = workflow.builtin() || bots.mode.get() != Bots.Mode.Host;
        add(theme.label(workflow.name() + (workflow.builtin() ? " · built-in, read-only" : " · custom"), true, contentWidth));
        add(theme.label(workflow.script().isEmpty()
            ? "Native highway preset: Excavating and Paving repeat for each road row; supplies are on-demand fallbacks. Use a Lua program for ordered tasks, decisions and interruptions."
            : "Lua program: return one native action at a time. ctx.state persists between actions; ctx.args contains task inputs and ctx.result the previous action result. No live coroutine is kept.", contentWidth)
            .color(theme.textSecondaryColor()));
        WTable metadata = add(theme.table()).expandX().widget();
        metadata.add(theme.label("Name"));
        if (readOnly) metadata.add(theme.label(workflow.name(), contentWidth - 80)).expandX();
        else name = metadata.add(theme.textBox(workflow.name())).minWidth(210).expandX().widget();
        metadata.row(); metadata.add(theme.label("Folder"));
        if (readOnly) metadata.add(theme.label(workflow.folder(), contentWidth - 80)).expandX();
        else folder = metadata.add(theme.textBox(workflow.folder(), "Highway Builder")).expandX().widget();
        feedback = add(theme.label(readOnly ? "Read-only definition. The host can duplicate it to customize."
            : "Changes stay in this draft until Save. Existing tasks keep their captured code and profiles.", contentWidth)).expandX().widget();
        if (!workflow.script().isEmpty()) {
            try { programEditor(); } catch (RuntimeException e) { feedback.set("Program references unavailable: " + message(e)); feedback.color(new Color(230, 139, 147)); }
        }
        else {
            steps = add(theme.verticalList()).expandX().widget();
            rebuildSteps();
        }
        if (!readOnly && workflow.script().isEmpty()) {
            WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
            actions.add(theme.button("Add action")).widget().action = () -> { draft.add(new BotWorkflows.Step(BotWorkflows.Action.Paving, "")); rebuildSteps(); };
            actions.add(theme.button("Add workflow call")).widget().action = () -> { draft.add(new BotWorkflows.Step(BotWorkflows.Action.Call, firstTarget())); rebuildSteps(); };
            actions.add(theme.button("Save workflow")).widget().action = () -> perform(() -> {
                writable();
                workflow = bots.workflows().save(id, name.get(), folder.get(), List.copyOf(draft));
            }, "Workflow saved. Existing jobs keep their captured definitions; new captures use this version.");
        }
        add(theme.button("Copy shareable definition")).expandX().widget().action = () -> perform(() ->
            mc.keyboardHandler.setClipboard(new GsonBuilder().setPrettyPrinting().create().toJson(bots.workflows().exportDefinition(id))),
            "Saved definition copied. Import creates a new custom ID; review dependencies and profiles before running.");
        add(theme.button("Copy workflow ID for bot.call")).expandX().widget().action = () -> { mc.keyboardHandler.setClipboard(id); feedback.set("Stable workflow ID copied."); };
        if (bots.mode.get() == Bots.Mode.Host) {
            add(theme.horizontalSeparator("Duplicate into your library")).expandX();
            WTable copy = add(theme.table()).expandX().widget();
            copy.add(theme.label("New name"));
            WTextBox copyName = copy.add(theme.textBox(workflow.name().substring(0, Math.min(42, workflow.name().length())) + " copy")).expandX().widget();
            copy.row(); copy.add(theme.label("Folder"));
            WTextBox copyFolder = copy.add(theme.textBox(workflow.folder())).expandX().widget();
            add(theme.button("Duplicate saved definition")).expandX().widget().action = () -> perform(() -> {
                writable();
                BotWorkflows.Workflow created = bots.workflows().duplicate(id, copyName.get(), copyFolder.get());
                mc.gui.setScreen(new WorkflowEditorScreen(theme, bots, created.id()));
            }, "Custom copy created.");
            if (!workflow.builtin()) {
                var remove = add(theme.confirmedButton("Delete custom workflow", "Delete definition?")).expandX().widget();
                remove.action = () -> perform(() -> { writable(); bots.workflows().delete(id); discardRequested = true; }, "Custom workflow deleted.");
            }
        }
        add(theme.button("Back to Workers")).expandX().widget().action = this::onClose;
    }

    private void programEditor() {
        code = add(new WorkflowCodeBox(workflow.script(), readOnly, 14)).expandX().minWidth(contentWidth).widget();
        code.action = () -> { feedback.set("Unsaved Lua changes. Save validates syntax; it never runs this draft."); feedback.color(theme.textSecondaryColor()); };
        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        actions.add(theme.button("Copy code")).widget().action = () -> { mc.keyboardHandler.setClipboard(code.get()); feedback.set("Draft source copied."); };
        if (!readOnly) {
            var paste = actions.add(theme.confirmedButton("Paste code", "Replace draft code?")).widget();
            paste.action = () -> perform(() -> code.replace(mc.keyboardHandler.getClipboard()), "Clipboard loaded into draft. Review and Save to validate.");
            actions.add(theme.button("Validate & save")).widget().action = () -> perform(() -> {
                writable(); workflow = bots.workflows().saveProgram(id, name.get(), folder.get(), code.get(), List.copyOf(dependencies), List.copyOf(profiles));
            }, "Lua syntax validated and program saved. Already queued tasks retain their captured version.");
        }
        WSection references = add(theme.section("Called programs & shared tool profiles", false)).expandX().widget();
        references.add(theme.label("Declare workflows used by bot.call and profiles used by bot.profile. Profiles temporarily apply to subsequent task actions; personal settings return on suspension or completion. Captured definitions belong to the task; library edits do not alter running work.", contentWidth - 20).color(theme.textSecondaryColor()));
        for (BotWorkflows.Workflow target : bots.workflows().all()) {
            if (target.id().equals(id)) continue;
            if (readOnly) { if (dependencies.contains(target.id())) references.add(theme.label("Calls " + target.folder() + " / " + target.name(), contentWidth - 20)); }
            else {
                WHorizontalList row = references.add(theme.horizontalList()).expandX().widget();
                var checkbox = row.add(theme.checkbox(dependencies.contains(target.id()))).widget();
                row.add(theme.label(target.folder() + " / " + target.name(), contentWidth - 55)).expandX();
                checkbox.action = () -> { if (checkbox.checked) dependencies.add(target.id()); else dependencies.remove(target.id()); };
            }
        }
        references.add(theme.horizontalSeparator("Tool profile snapshots")).expandX();
        Set<String> names = new LinkedHashSet<>(BotProfiles.names()); names.addAll(profiles);
        for (String profile : names) {
            if (readOnly) { if (profiles.contains(profile)) references.add(theme.label(profile, contentWidth - 20)); }
            else {
                WHorizontalList row = references.add(theme.horizontalList()).expandX().widget();
                var checkbox = row.add(theme.checkbox(profiles.contains(profile))).widget();
                row.add(theme.label(profile, contentWidth - 55)).expandX();
                checkbox.action = () -> { if (checkbox.checked) profiles.add(profile); else profiles.remove(profile); };
            }
        }
        WSection help = add(theme.section("Lua API quick reference", false)).expandX().widget();
        help.add(theme.label("Return bot.done(), bot.wait(ticks), bot.call(id, args), bot.profile(name), bot.highway(args), bot.travel(args), bot.tpa(args), bot.modules(args), or bot.drop(args).\nUse ctx.state for resumable decisions. Task arguments are in ctx.args; observations are in ctx.world. Higher-priority tasks interrupt only after native recovery reaches a safe checkpoint.\nRead the bundled examples for exact action arguments. Copy a workflow's stable ID from its shareable definition when declaring calls.", contentWidth - 20).color(theme.textSecondaryColor()));
    }

    private void rebuildSteps() {
        steps.clear();
        if (draft.isEmpty()) steps.add(theme.label("No actions yet. Add a work action, a supply fallback or a nested workflow call.", contentWidth));
        for (int i = 0; i < draft.size(); i++) {
            final int index = i;
            BotWorkflows.Step step = draft.get(i);
            WVerticalList entry = steps.add(theme.verticalList()).expandX().widget();
            WHorizontalList row = entry.add(theme.horizontalList()).expandX().widget();
            row.add(theme.label((i + 1) + "."));
            if (readOnly) row.add(theme.label(step.action().toString())).expandX();
            else {
                WDropdown<BotWorkflows.Action> action = row.add(theme.dropdown(step.action())).expandX().widget();
                action.action = () -> {
                    draft.set(index, new BotWorkflows.Step(action.get(), action.get() == BotWorkflows.Action.Call
                        ? step.action() == BotWorkflows.Action.Call ? step.target() : firstTarget() : ""));
                    rebuildSteps();
                };
                if (i > 0) row.add(theme.button("Up")).widget().action = () -> { Collections.swap(draft, index, index - 1); rebuildSteps(); };
                if (i + 1 < draft.size()) row.add(theme.button("Down")).widget().action = () -> { Collections.swap(draft, index, index + 1); rebuildSteps(); };
                row.add(theme.minus()).widget().action = () -> { draft.remove(index); rebuildSteps(); };
            }
            if (step.action() == BotWorkflows.Action.Call) {
                Target[] targets = targets();
                Target target = java.util.Arrays.stream(targets).filter(t -> t.id().equals(step.target())).findFirst().orElse(new Target(step.target(), "Missing workflow: " + step.target()));
                if (readOnly) entry.add(theme.label("Calls " + target.label(), contentWidth - 20).color(theme.textSecondaryColor()));
                else if (targets.length == 0) entry.add(theme.label("No other workflows to call. Create a custom copy of another definition first.", contentWidth - 20));
                else {
                    WDropdown<Target> call = entry.add(theme.dropdown(targets, target)).expandX().widget();
                    call.action = () -> draft.set(index, new BotWorkflows.Step(BotWorkflows.Action.Call, call.get().id()));
                }
            }
        }
    }

    private Target[] targets() {
        return bots.workflows().all().stream().filter(w -> !w.id().equals(id) && w.script().isEmpty())
            .map(w -> new Target(w.id(), w.folder() + " / " + w.name())).toArray(Target[]::new);
    }
    private String firstTarget() { Target[] choices = targets(); return choices.length == 0 ? "" : choices[0].id(); }
    private void writable() { if (bots.mode.get() != Bots.Mode.Host) throw new IllegalStateException("Only the host can edit workflow definitions."); }
    private static String message(RuntimeException e) { return e.getMessage() == null ? "Unable to update this workflow." : e.getMessage(); }
    private void perform(Runnable action, String success) {
        try { action.run(); feedback.set(success); feedback.color(new Color(115, 211, 181)); }
        catch (RuntimeException e) { feedback.set(message(e)); feedback.color(new Color(230, 139, 147)); }
    }
    private boolean unsaved() {
        return !readOnly && workflow != null && name != null && (!name.get().strip().equals(workflow.name()) || !folder.get().strip().equals(workflow.folder())
            || code != null && (!code.get().equals(workflow.script()) || !dependencies.equals(Set.copyOf(workflow.dependencies())) || !profiles.equals(Set.copyOf(workflow.profiles())))
            || code == null && !draft.equals(workflow.steps()));
    }
    @Override public void onClose() {
        if (!unsaved()) { super.onClose(); return; }
        YesNoPrompt.create(theme, this).title("Discard unsaved workflow changes?")
            .message("Choose No to return to the editor and Save your draft.")
            .dontShowAgainCheckboxVisible(false).onYes(() -> discardRequested = true).show();
    }
    @Override public void tick() {
        super.tick();
        if (discardRequested) { discardRequested = false; super.onClose(); }
    }
}
