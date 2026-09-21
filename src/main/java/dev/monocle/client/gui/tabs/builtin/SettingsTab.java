package dev.monocle.client.gui.tabs.builtin;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.tabs.*;
import net.minecraft.client.gui.screens.Screen;

/** Existing configuration editors, grouped without duplicating their settings. */
public final class SettingsTab extends Tab {
    public SettingsTab() { super("Settings"); }

    @Override public TabScreen createScreen(GuiTheme theme) {
        return new WindowTabScreen(theme, this) {
            @Override public void initWidgets() {
                add(theme.label("Make Monocle yours", true));
                add(theme.label("Client preferences, appearance, and personal shortcuts.", 360));
                for (Tab destination : Tabs.get()) if (Tabs.secondary(destination)) {
                    add(theme.button(destination.name.equals("GUI") ? "Appearance & layout" : destination.name))
                        .expandX().widget().action = () -> destination.openScreen(theme);
                }
            }
        };
    }

    @Override public boolean isScreen(Screen screen) {
        return screen instanceof TabScreen s && (s.tab == this || Tabs.secondary(s.tab));
    }
}
