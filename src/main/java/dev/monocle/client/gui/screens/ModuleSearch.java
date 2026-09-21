package dev.monocle.client.gui.screens;

import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.settings.Settings;

import java.util.Locale;

/** Every word must match somewhere in the searchable module text. */
public final class ModuleSearch {
    private ModuleSearch() {}
    public static boolean matches(String query, String document) {
        String text = document.toLowerCase(Locale.ROOT);
        for (String word : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!text.contains(word)) return false;
        }
        return true;
    }

    public static int count(Settings settings, String query) {
        int count = 0;
        for (SettingGroup group : settings) for (var setting : group) {
            if (setting.isVisible() && matches(query, group.name + " " + setting.title + " " + setting.description)) count++;
        }
        return count;
    }

    public static boolean advancedGroup(String name) {
        return name.toLowerCase(Locale.ROOT).contains("advanced");
    }
}
