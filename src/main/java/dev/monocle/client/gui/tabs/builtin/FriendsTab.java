/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.tabs.builtin;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.tabs.Tab;
import dev.monocle.client.gui.tabs.TabScreen;
import dev.monocle.client.gui.tabs.WindowTabScreen;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WTable;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.gui.widgets.pressable.WMinus;
import dev.monocle.client.gui.widgets.pressable.WPlus;
import dev.monocle.client.systems.friends.Friend;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.utils.misc.NbtUtils;
import dev.monocle.client.utils.network.MonocleExecutor;
import net.minecraft.client.gui.screens.Screen;

import static dev.monocle.client.MonocleClient.mc;

public class FriendsTab extends Tab {
    public FriendsTab() {
        super("Friends");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new FriendsScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof FriendsScreen;
    }

    private static class FriendsScreen extends WindowTabScreen {
        public FriendsScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            WTable table = add(theme.table()).expandX().minWidth(400).widget();
            initTable(table);

            add(theme.horizontalSeparator()).expandX();

            // New
            WHorizontalList list = add(theme.horizontalList()).expandX().widget();

            WTextBox nameW = list.add(theme.textBox("", (_, c) -> c != ' ')).expandX().widget();
            nameW.setFocused(true);

            WPlus add = list.add(theme.plus()).widget();
            add.action = () -> {
                String name = nameW.get().trim();
                Friend friend = new Friend(name);

                if (Friends.get().add(friend)) {
                    nameW.set("");
                    initTable(table);
                    nameW.setFocused(true);

                    MonocleExecutor.execute(() -> {
                        friend.updateInfo();
                        mc.execute(() -> {
                            initTable(table);
                            nameW.setFocused(true);
                        });
                    });
                }
            };

            enterAction = add.action;
        }

        private void initTable(WTable table) {
            table.clear();
            if (Friends.get().isEmpty()) return;

            Friends.get().forEach(friend ->
                MonocleExecutor.execute(() -> {
                    if (friend.headTextureNeedsUpdate()) {
                        friend.updateInfo();
                    }
                })
            );

            for (Friend friend : Friends.get()) {
                table.add(theme.texture(32, 32, friend.getHead().needsRotate() ? 90 : 0, friend.getHead()));
                table.add(theme.label(friend.getName()));

                WMinus remove = table.add(theme.minus()).expandCellX().right().widget();
                remove.action = () -> {
                    Friends.get().remove(friend);
                    initTable(table);
                };

                table.row();
            }
        }

        @Override
        public boolean toClipboard() {
            return NbtUtils.toClipboard(Friends.get());
        }

        @Override
        public boolean fromClipboard() {
            return NbtUtils.fromClipboard(Friends.get());
        }
    }
}
