package dev.monocle.client.gui.screens;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.utils.Utils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.monocle.client.MonocleClient.mc;

/** Inspection never applies a profile or changes a running task. */
public final class TaskConfigurationScreen extends WindowScreen {
    private record Source(String key, String name, boolean live) {
        @Override public String toString() { return name; }
    }
    private final Bots bots;
    private final UUID task;
    private JsonObject snapshot;
    private WDropdown<Source> source;
    private WVerticalList modules;
    private WLabel feedback;
    private double width;
    private String selectedModule = "";

    public TaskConfigurationScreen(GuiTheme theme, Bots bots, UUID task) {
        super(theme, "Job configuration"); this.bots = bots; this.task = task;
    }

    @Override public void initWidgets() {
        width = Math.clamp(Utils.getWindowWidth() / theme.scale(1) - 100, 330, 700);
        feedback = add(theme.label("Read-only snapshot. Refresh to check new acknowledgements.", width)).expandX().widget();
        try {
            snapshot = bots.tasks().configuration(task);
            if (snapshot.has("attribution")) add(theme.label(snapshot.get("attribution").getAsString(),width)).expandX();
            add(theme.label(snapshot.get("explanation").getAsString(), width).color(theme.textSecondaryColor())).expandX();
            add(theme.button("Compare personal / host / actual settings")).expandX().widget().action = () ->
                mc.gui.setScreen(new ConfigurationComparisonScreen(theme,bots,task));
            List<Source> sources = new ArrayList<>();
            snapshot.getAsJsonObject("profiles").keySet().stream().sorted().forEach(name -> sources.add(new Source(name, "Captured profile · " + name, false)));
            snapshot.getAsJsonObject("updates").keySet().forEach(id -> sources.add(new Source(id, "Latest live request · " + workerName(id), true)));
            if (!sources.isEmpty()) {
                source = add(theme.dropdown(sources.toArray(Source[]::new), sources.getFirst())).expandX().widget();
                modules = add(theme.verticalList()).expandX().widget();
                source.action = this::showModules;
                showModules();
            }
            var plans = add(theme.section("Captured highway capabilities", false)).expandX().widget();
            for (var entry : snapshot.getAsJsonObject("highways").entrySet()) {
                JsonObject plan = entry.getValue().getAsJsonObject();
                plans.add(theme.label(entry.getKey() + "\nDuty: " + plan.get("duty") + "\nCapabilities: " + plan.get("actions"), width - 20)).expandX();
            }
            add(theme.button("Refresh acknowledgements")).expandX().widget().action = () -> {
                try { snapshot = bots.tasks().configuration(task); if (source != null) showModules(); feedback.set("Snapshot refreshed. No settings were changed."); }
                catch (RuntimeException e) { feedback.set("Refresh failed: " + e.getMessage()); }
            };
            add(theme.button("Copy configuration report")).expandX().widget().action = () -> {
                mc.keyboardHandler.setClipboard(new GsonBuilder().setPrettyPrinting().create().toJson(snapshot));
                feedback.set("Copied captured profiles, supply capabilities, and latest live requests.");
            };
            var save = add(theme.section("Save captured profile as personal copy…",false)).expandX().widget();
            save.add(theme.label("Creates a new profile on THIS client. Omitted settings inherit your personal settings. Does not apply it, overwrite existing profiles, or change any worker. Job executors remain off.",width-20)).expandX();
            String[] profiles=snapshot.getAsJsonObject("profiles").keySet().stream().sorted().toArray(String[]::new);
            if(profiles.length>0){
                var chosen=save.add(theme.dropdown(profiles,profiles[0])).expandX().widget();
                save.add(theme.label("New personal profile name"));
                var name=save.add(theme.textBox("Imported job profile")).expandX().widget();
                var confirm=save.add(theme.confirmedButton("Save personal copy","Create this local profile?")).expandX().widget();
                confirm.action=()->{
                    try{dev.monocle.client.systems.bots.BotProfiles.savePersonalCopy(name.get(),snapshot.getAsJsonObject("profiles").getAsJsonObject(chosen.get()));feedback.set("Saved personal profile '"+name.get()+"'. Nothing was applied.");}
                    catch(RuntimeException e){feedback.set("Copy failed: "+e.getMessage());}
                };
            }
        } catch (RuntimeException e) { feedback.set("Configuration unavailable: " + e.getMessage()); }
        add(theme.button("Back to task")).expandX().widget().action = this::onClose;
    }

    private String workerName(String id) {
        return bots.allMembers().stream().filter(member -> member.id().toString().equals(id)).map(member -> member.name()).findFirst().orElse(id);
    }

    private void showModules() {
        modules.clear();
        Source choice = source.get();
        JsonObject values;
        if (choice.live()) {
            JsonObject update = snapshot.getAsJsonObject("updates").getAsJsonObject(choice.key());
            modules.add(theme.label(update.get("status").getAsString() + " · requested revision " + update.get("requestedRevision")
                + " · acknowledged " + update.get("acknowledgedRevision") + "\n" + update.get("error").getAsString(), width)).expandX();
            values = update.getAsJsonObject("modules");
        } else values = snapshot.getAsJsonObject("profiles").getAsJsonObject(choice.key());
        if (values.isEmpty()) { modules.add(theme.label("No module overrides in this source.", width)); return; }
        String[] names = values.keySet().stream().sorted().toArray(String[]::new);
        String selected = values.has(selectedModule) ? selectedModule : names[0];
        WDropdown<String> module = modules.add(theme.dropdown(names, selected)).expandX().widget();
        WVerticalList details = modules.add(theme.verticalList()).expandX().widget();
        module.action = () -> { selectedModule = module.get(); showModule(details, values.getAsJsonObject(selectedModule)); };
        module.action.run();
    }

    private void showModule(WVerticalList details, JsonObject value) {
        details.clear();
        boolean executor = List.of("highway-builder", "printer-helper", "schematic-selector").contains(selectedModule);
        details.add(theme.label(executor ? "Activation is controlled by the job executor."
            : "Requested activation: " + (value.get("active").getAsBoolean() ? "On" : "Off"), width));
        String settings = value.get("settings").getAsString();
        try {
            CompoundTag tag = TagParser.parseCompoundFully(settings);
            boolean empty = true;
            for (Tag groupTag : tag.getListOrEmpty("groups")) {
                CompoundTag group = (CompoundTag) groupTag;
                StringBuilder text = new StringBuilder(group.getStringOr("name", "Settings"));
                for (Tag settingTag : group.getListOrEmpty("settings")) {
                    CompoundTag setting = (CompoundTag) settingTag;
                    text.append("\n").append(setting.getStringOr("name", "Setting")).append(": ").append(setting.get("value"));
                    empty = false;
                }
                details.add(theme.label(text.toString(), width)).expandX();
            }
            if (empty) details.add(theme.label("No explicit setting values. The worker retains its settings.", width)).expandX();
        } catch (Exception e) { details.add(theme.label("Could not decode settings; inspect the serialized values below.", width)); }
        var raw = details.add(theme.section("Serialized settings", false)).expandX().widget();
        WorkflowCodeBox code = raw.add(new WorkflowCodeBox(settings, true, 5)).minWidth(width - 20).expandX().widget();
        code.tooltip = "Read-only captured settings. Select and copy; this does not change the job.";
    }
}
