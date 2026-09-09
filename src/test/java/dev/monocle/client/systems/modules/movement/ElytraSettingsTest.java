package dev.monocle.client.systems.modules.movement;

import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** ./gradlew elytraSettingsCheck; profile migration without starting the renderer. */
public final class ElytraSettingsTest {
    public static void main(String[] args) {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        CompoundTag speed = new CompoundTag();
        speed.putString("name", "horizontal-speed");
        speed.putDouble("value", 2.6);
        CompoundTag ramp = new CompoundTag();
        ramp.putString("name", "acceleration-start");
        ramp.putDouble("value", 0.17);
        ListTag values = new ListTag();
        values.add(speed);
        values.add(ramp);
        CompoundTag general = new CompoundTag();
        general.putString("name", "General");
        general.put("settings", values);
        ListTag groups = new ListTag();
        groups.add(general);
        CompoundTag saved = new CompoundTag();
        saved.put("groups", groups);
        CompoundTag snapshot = saved.copy();
        CompoundTag flight = ElytraFly.regroupSettings(saved, "Flight", true, "horizontal-speed"::equals);
        CompoundTag acceleration = ElytraFly.regroupSettings(saved, "Acceleration", true, "acceleration-start"::equals);
        assert flight.getListOrEmpty("settings").size() == 1;
        assert flight.getListOrEmpty("settings").get(0).equals(speed);
        assert acceleration.getListOrEmpty("settings").get(0).equals(ramp);
        assert saved.equals(snapshot) : "Never mutate input profiles";
        flight.putBoolean("sectionExpanded", false);
        groups.clear();
        groups.add(flight);
        groups.add(acceleration);
        assert ElytraFly.regroupSettings(saved, "Flight", true, "horizontal-speed"::equals).equals(flight);
        assert ElytraFly.regroupSettings(saved, "Acceleration", true, "acceleration-start"::equals).equals(acceleration);
        assert ElytraFly.regroupSettings(new CompoundTag(), "Flight", true, name -> true).getListOrEmpty("settings").isEmpty();
        groups.add(net.minecraft.nbt.StringTag.valueOf("ignore malformed entry"));
        ElytraFly.regroupSettings(saved, "Flight", true, name -> true);
        System.out.println("Elytra settings checks passed: exact legacy values, routing, non-mutating migration, round-trip, expansion state and missing settings.");
    }
}
