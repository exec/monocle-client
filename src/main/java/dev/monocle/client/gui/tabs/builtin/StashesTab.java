package dev.monocle.client.gui.tabs.builtin;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.screens.StashManagerScreen;
import dev.monocle.client.gui.tabs.*;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.world.StashManager;
import net.minecraft.client.gui.screens.Screen;

public final class StashesTab extends Tab {
    public StashesTab() { super("Stashes"); }
    @Override public TabScreen createScreen(GuiTheme theme) {
        return new StashManagerScreen(theme, Modules.get().get(StashManager.class), true);
    }
    @Override public boolean isScreen(Screen screen) { return screen instanceof StashManagerScreen; }
}
