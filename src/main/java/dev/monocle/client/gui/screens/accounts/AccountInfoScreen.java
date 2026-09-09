/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.screens.accounts;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.systems.accounts.Account;
import dev.monocle.client.systems.accounts.AccountType;
import dev.monocle.client.systems.accounts.TokenAccount;
import dev.monocle.client.utils.render.color.Color;

import static dev.monocle.client.MonocleClient.mc;

public class AccountInfoScreen extends WindowScreen {
    private final Account<?> account;

    public AccountInfoScreen(GuiTheme theme, Account<?> account) {
        super(theme, account.getUsername() + " details");
        this.account = account;
    }

    @Override
    public void initWidgets() {
        TokenAccount e = (TokenAccount) account;
        WHorizontalList l = add(theme.horizontalList()).expandX().widget();

        String tokenLabel = account.getType() + " token:";
        if (account.getType() == AccountType.Session) tokenLabel = "";

        WButton copy = theme.button("Copy");
        copy.action = () -> mc.keyboardHandler.setClipboard(e.getToken());

        l.add(theme.label(tokenLabel));
        l.add(theme.label(account.getType() == AccountType.Session ? "Click to copy Token" : e.getToken()).color(Color.GRAY)).pad(5);
        l.add(copy);
    }
}
