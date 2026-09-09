/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens.accounts;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.systems.accounts.MicrosoftLogin;
import dev.monocle.client.systems.accounts.types.MicrosoftAccount;

import static dev.monocle.client.MonocleClient.mc;

public class AddMicrosoftAccountScreen extends AddAccountScreen {
    public AddMicrosoftAccountScreen(GuiTheme theme, AccountsScreen parent) {
        super(theme, "Add Microsoft Account", parent);
    }

    @Override
    public void initWidgets() {
        String url = MicrosoftLogin.getRefreshToken(refreshToken -> {

            if (refreshToken != null) {
                MicrosoftAccount account = new MicrosoftAccount(refreshToken);
                AccountsScreen.addAccount(null, parent, account);
            }

            onClose();
        });

        add(theme.label("Please select the account to log into in your browser."));
        add(theme.label("If the link does not automatically open in a few seconds, copy it into your browser."));

        WHorizontalList l = add(theme.horizontalList()).expandX().widget();

        WButton copy = l.add(theme.button("Copy link")).expandX().widget();
        copy.action = () -> mc.keyboardHandler.setClipboard(url);

        WButton cancel = l.add(theme.button("Cancel")).expandX().widget();
        cancel.action = () -> {
            MicrosoftLogin.cancelLogin();
            onClose();
        };
    }

    @Override
    public void tick() {
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
