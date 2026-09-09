/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.render.prompts;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.GuiThemes;
import dev.monocle.client.gui.widgets.pressable.WButton;
import net.minecraft.client.gui.screens.Screen;

import static dev.monocle.client.MonocleClient.mc;

public class YesNoPrompt extends Prompt<YesNoPrompt> {
    private Runnable onYes = () -> {
    };
    private Runnable onNo = () -> {
    };

    private YesNoPrompt(GuiTheme theme, Screen parent) {
        super(theme, parent);
    }

    public static YesNoPrompt create() {
        return new YesNoPrompt(GuiThemes.get(), mc.gui.screen());
    }

    public static YesNoPrompt create(GuiTheme theme, Screen parent) {
        return new YesNoPrompt(theme, parent);
    }

    public YesNoPrompt onYes(Runnable action) {
        this.onYes = action;
        return this;
    }

    public YesNoPrompt onNo(Runnable action) {
        this.onNo = action;
        return this;
    }

    @Override
    protected void initialiseWidgets(PromptScreen screen) {
        WButton yesButton = screen.list.add(theme.button("Yes")).expandX().widget();
        yesButton.action = () -> {
            dontShowAgain(screen);
            onYes.run();
            screen.onClose();
        };

        WButton noButton = screen.list.add(theme.button("No")).expandX().widget();
        noButton.action = () -> {
            dontShowAgain(screen);
            onNo.run();
            screen.onClose();
        };
    }
}
