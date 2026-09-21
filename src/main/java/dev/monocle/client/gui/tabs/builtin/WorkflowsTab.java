package dev.monocle.client.gui.tabs.builtin;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.tabs.*;
import net.minecraft.client.gui.screens.Screen;

public final class WorkflowsTab extends Tab {
    public WorkflowsTab() { super("Workflows"); }
    @Override public TabScreen createScreen(GuiTheme theme) { return BotsTab.workflowsScreen(theme, this); }
    @Override public boolean isScreen(Screen screen) { return screen instanceof TabScreen s && s.tab == this; }
}
