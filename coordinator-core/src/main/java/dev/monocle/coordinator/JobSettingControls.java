package dev.monocle.coordinator;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;

/** Small shared catalog for the guided editor; all writes use the existing configuration protocol. */
public final class JobSettingControls {
    public record Control(String id, String module, String label, String group, String setting,
                          double min, double max, double step, double example, String help) {
        public boolean numeric() { return !setting.isEmpty(); }
        @Override public String toString() { return label; }
    }
    public static final List<Control> ALL = List.of(
        new Control("speed", "speed", "Speed · Vanilla speed", "General", "vanilla-speed", 0, 20, .1, 5.5,
            "Blocks per second in Vanilla mode. Does not change the selected mode."),
        new Control("elytra-speed", "elytra-fly", "Elytra Fly · Horizontal speed", "Flight", "horizontal-speed", 0, 10, .1, 1,
            "Vanilla mode uses blocks per tick: 1 = 20 blocks/sec at 20 TPS. Does not change flight mode or acceleration."),
        new Control("eat-hunger", "auto-eat", "Auto Eat · Hunger threshold", "Threshold", "hunger-threshold", 1, 19, 1, 16,
            "Starts eating at this hunger level. The existing threshold mode and food protections still apply."),
        new Control("eat-health", "auto-eat", "Auto Eat · Health threshold", "Threshold", "health-threshold", 1, 19, .5, 10,
            "Health points, not hearts. The existing threshold mode and food protections still apply."),
        toggle("auto-eat", "Auto Eat"), toggle("auto-tool", "Auto Tool"), toggle("velocity", "Velocity"),
        toggle("speed", "Speed"), toggle("elytra-fly", "Elytra Fly")
    );
    private JobSettingControls() { }
    private static Control toggle(String module, String label) {
        return new Control("toggle-" + module, module, label + " · Activation only", "", "", 0, 0, 0, 0,
            "Changes activation only; all existing module settings are retained.");
    }
    public static JsonObject catalog() {
        JsonObject result = new JsonObject(); result.add("controls", new Gson().toJsonTree(ALL)); return result;
    }
    public static JsonObject preview(JsonObject request) {
        Control control = ALL.stream().filter(c -> c.id().equals(TaskWire.text(request, "control"))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown guided control"));
        if (!request.has("active") || !request.get("active").isJsonPrimitive() || !request.getAsJsonPrimitive("active").isBoolean())
            throw new IllegalArgumentException("Choose On or Off explicitly");
        boolean active = request.get("active").getAsBoolean();
        String settings = "{}", description = control.label() + " · module " + (active ? "On" : "Off");
        if (control.numeric()) {
            if (!request.has("value") || !request.get("value").isJsonPrimitive() || !request.getAsJsonPrimitive("value").isNumber())
                throw new IllegalArgumentException("Enter a numeric value");
            double value = request.get("value").getAsDouble();
            if (!Double.isFinite(value) || value < control.min() || value > control.max() || control.step() == 1 && value != Math.rint(value))
                throw new IllegalArgumentException("Value must be " + control.min() + "–" + control.max() + (control.step() == 1 ? " in whole numbers" : ""));
            String literal = control.step() == 1 ? Integer.toString((int) value) : Double.toString(value) + "d";
            settings = "{groups:[{name:\"" + control.group() + "\",settings:[{name:\"" + control.setting() + "\",value:" + literal + "}]}]}";
            description += " · " + control.setting() + " = " + value;
        }
        JsonObject module = new JsonObject(), modules = new JsonObject(), result = new JsonObject();
        module.addProperty("active", active); module.addProperty("settings", settings); modules.add(control.module(), module);
        result.add("modules", TaskWire.checkedConfiguration(modules));
        result.addProperty("description", description);
        result.addProperty("scope", "This job only. Other settings and future-job defaults are unchanged. Acknowledgement confirms application, not permanent ownership of the module.");
        return result;
    }
}
