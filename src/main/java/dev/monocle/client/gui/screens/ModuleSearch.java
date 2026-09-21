package dev.monocle.client.gui.screens;

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
}
