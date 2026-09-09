/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.themes.monocle;

import com.mojang.blaze3d.platform.MacosUtil;
import dev.monocle.client.gui.DefaultSettingsWidgetFactory;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.gui.renderer.packer.GuiTexture;
import dev.monocle.client.gui.themes.monocle.widgets.*;
import dev.monocle.client.gui.themes.monocle.widgets.input.WMonocleDropdown;
import dev.monocle.client.gui.themes.monocle.widgets.input.WMonocleSlider;
import dev.monocle.client.gui.themes.monocle.widgets.input.WMonocleTextBox;
import dev.monocle.client.gui.themes.monocle.widgets.pressable.*;
import dev.monocle.client.gui.utils.AlignmentX;
import dev.monocle.client.gui.utils.CharFilter;
import dev.monocle.client.gui.widgets.*;
import dev.monocle.client.gui.widgets.containers.WSection;
import dev.monocle.client.gui.widgets.containers.WView;
import dev.monocle.client.gui.widgets.containers.WWindow;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.gui.widgets.input.WSlider;
import dev.monocle.client.gui.widgets.input.WTextBox;
import dev.monocle.client.gui.widgets.pressable.*;
import dev.monocle.client.renderer.text.TextRenderer;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.accounts.Account;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.render.color.SettingColor;

import static dev.monocle.client.MonocleClient.mc;

public class MonocleGuiTheme extends GuiTheme {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgTextColors = settings.createGroup("Text");
    private final SettingGroup sgBackgroundColors = settings.createGroup("Background");
    private final SettingGroup sgOutline = settings.createGroup("Outline");
    private final SettingGroup sgSeparator = settings.createGroup("Separator");
    private final SettingGroup sgScrollbar = settings.createGroup("Scrollbar");
    private final SettingGroup sgSlider = settings.createGroup("Slider");
    private final SettingGroup sgStarscript = settings.createGroup("Starscript");

    // General

    public final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the GUI.")
        .defaultValue(1)
        .min(0.75)
        .sliderRange(0.75, 4)
        .onSliderRelease()
        .onChanged(_ -> {
            if (mc.gui.screen() instanceof WidgetScreen widgetScreen) widgetScreen.invalidate();
        })
        .build()
    );

    public final Setting<AlignmentX> moduleAlignment = sgGeneral.add(new EnumSetting.Builder<AlignmentX>()
        .name("module-alignment")
        .description("How module titles are aligned.")
        .defaultValue(AlignmentX.Center)
        .build()
    );

    public final Setting<Boolean> categoryIcons = sgGeneral.add(new BoolSetting.Builder()
        .name("category-icons")
        .description("Adds item icons to module categories.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> modulesHelpText = sgGeneral.add(new BoolSetting.Builder()
        .name("modules-help-text")
        .description("Toggle help text in the modules screen.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> hideHUD = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-HUD")
        .description("Hide HUD when in GUI.")
        .defaultValue(false)
        .onChanged(v -> {
            if (mc.gui.screen() instanceof WidgetScreen) {
                mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden = v;
            }
        })
        .build()
    );

    // Colors

    public final Setting<SettingColor> accentColor = color("accent", "Main color of the GUI.", new SettingColor(204, 182, 139));
    public final Setting<SettingColor> checkboxColor = color("checkbox", "Color of checkbox.", new SettingColor(115, 211, 181));
    public final Setting<SettingColor> plusColor = color("plus", "Color of plus button.", new SettingColor(115, 211, 181));
    public final Setting<SettingColor> minusColor = color("minus", "Color of minus button.", new SettingColor(219, 122, 133));
    public final Setting<SettingColor> favoriteColor = color("favorite", "Color of checked favorite button.", new SettingColor(218, 188, 120));

    // Text

    public final Setting<SettingColor> textColor = color(sgTextColors, "text", "Color of text.", new SettingColor(235, 231, 220));
    public final Setting<SettingColor> textSecondaryColor = color(sgTextColors, "text-secondary-text", "Color of secondary text.", new SettingColor(156, 166, 176));
    public final Setting<SettingColor> textHighlightColor = color(sgTextColors, "text-highlight", "Color of text highlighting.", new SettingColor(115, 211, 181, 70));
    public final Setting<SettingColor> titleTextColor = color(sgTextColors, "title-text", "Color of title text.", new SettingColor(247, 237, 214));
    public final Setting<SettingColor> loggedInColor = color(sgTextColors, "logged-in-text", "Color of logged in account name.", new SettingColor(115, 211, 181));
    public final Setting<SettingColor> placeholderColor = color(sgTextColors, "placeholder", "Color of placeholder text.", new SettingColor(144, 153, 163));

    // Background

    public final ThreeStateColorSetting backgroundColor = new ThreeStateColorSetting(
        sgBackgroundColors,
        "background",
        new SettingColor(20, 24, 31, 245),
        new SettingColor(32, 38, 46, 250),
        new SettingColor(12, 16, 23)
    );

    public final Setting<SettingColor> moduleBackground = color(sgBackgroundColors, "module-background", "Color of module background when active.", new SettingColor(32, 60, 56, 235));

    // Outline

    public final ThreeStateColorSetting outlineColor = new ThreeStateColorSetting(
        sgOutline,
        "outline",
        new SettingColor(56, 63, 73),
        new SettingColor(146, 131, 105),
        new SettingColor(204, 182, 139)
    );

    // Separator

    public final Setting<SettingColor> separatorText = color(sgSeparator, "separator-text", "Color of separator text", new SettingColor(183, 171, 148));
    public final Setting<SettingColor> separatorCenter = color(sgSeparator, "separator-center", "Center color of separators.", new SettingColor(85, 78, 67));
    public final Setting<SettingColor> separatorEdges = color(sgSeparator, "separator-edges", "Color of separator edges.", new SettingColor(49, 54, 62, 200));

    // Scrollbar

    public final ThreeStateColorSetting scrollbarColor = new ThreeStateColorSetting(
        sgScrollbar,
        "Scrollbar",
        new SettingColor(78, 88, 98, 210),
        new SettingColor(142, 133, 113),
        new SettingColor(204, 182, 139)
    );

    // Slider

    public final ThreeStateColorSetting sliderHandle = new ThreeStateColorSetting(
        sgSlider,
        "slider-handle",
        new SettingColor(225, 221, 207),
        new SettingColor(248, 240, 222),
        new SettingColor(115, 211, 181)
    );

    public final Setting<SettingColor> sliderLeft = color(sgSlider, "slider-left", "Color of slider left part.", new SettingColor(100, 190, 164));
    public final Setting<SettingColor> sliderRight = color(sgSlider, "slider-right", "Color of slider right part.", new SettingColor(47, 56, 66));

    // Starscript

    private final Setting<SettingColor> starscriptText = color(sgStarscript, "starscript-text", "Color of text in Starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptBraces = color(sgStarscript, "starscript-braces", "Color of braces in Starscript code.", new SettingColor(150, 150, 150));
    private final Setting<SettingColor> starscriptParenthesis = color(sgStarscript, "starscript-parenthesis", "Color of parenthesis in Starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptDots = color(sgStarscript, "starscript-dots", "Color of dots in starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptCommas = color(sgStarscript, "starscript-commas", "Color of commas in starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptOperators = color(sgStarscript, "starscript-operators", "Color of operators in Starscript code.", new SettingColor(169, 183, 198));
    private final Setting<SettingColor> starscriptStrings = color(sgStarscript, "starscript-strings", "Color of strings in Starscript code.", new SettingColor(106, 135, 89));
    private final Setting<SettingColor> starscriptNumbers = color(sgStarscript, "starscript-numbers", "Color of numbers in Starscript code.", new SettingColor(104, 141, 187));
    private final Setting<SettingColor> starscriptKeywords = color(sgStarscript, "starscript-keywords", "Color of keywords in Starscript code.", new SettingColor(204, 120, 50));
    private final Setting<SettingColor> starscriptAccessedObjects = color(sgStarscript, "starscript-accessed-objects", "Color of accessed objects (before a dot) in Starscript code.", new SettingColor(152, 118, 170));

    public MonocleGuiTheme() {
        super("Monocle");

        settingsFactory = new DefaultSettingsWidgetFactory(this);
    }

    private Setting<SettingColor> color(SettingGroup group, String name, String description, SettingColor color) {
        return group.add(new ColorSetting.Builder()
            .name(name + "-color")
            .description(description)
            .defaultValue(color)
            .build());
    }

    private Setting<SettingColor> color(String name, String description, SettingColor color) {
        return color(sgColors, name, description, color);
    }

    // Widgets

    @Override
    public WWindow window(WWidget icon, String title) {
        return w(new WMonocleWindow(icon, title));
    }

    @Override
    public WLabel label(String text, boolean title, double maxWidth) {
        if (maxWidth == 0 && !text.contains("\n")) return w(new WMonocleLabel(text, title));
        return w(new WMonocleMultiLabel(text, title, maxWidth));
    }

    @Override
    public WHorizontalSeparator horizontalSeparator(String text) {
        return w(new WMonocleHorizontalSeparator(text));
    }

    @Override
    public WVerticalSeparator verticalSeparator() {
        return w(new WMonocleVerticalSeparator());
    }

    @Override
    protected WButton button(String text, GuiTexture texture) {
        return w(new WMonocleButton(text, texture));
    }

    @Override
    protected WConfirmedButton confirmedButton(String text, String confirmText, GuiTexture texture) {
        return w(new WMonocleConfirmedButton(text, confirmText, texture));
    }

    @Override
    public WMinus minus() {
        return w(new WMonocleMinus());
    }

    @Override
    public WConfirmedMinus confirmedMinus() {
        return w(new WMonocleConfirmedMinus());
    }

    @Override
    public WPlus plus() {
        return w(new WMonoclePlus());
    }

    @Override
    public WCheckbox checkbox(boolean checked) {
        return w(new WMonocleCheckbox(checked));
    }

    @Override
    public WSlider slider(double value, double min, double max) {
        return w(new WMonocleSlider(value, min, max));
    }

    @Override
    public WTextBox textBox(String text, String placeholder, CharFilter filter, Class<? extends WTextBox.Renderer> renderer) {
        return w(new WMonocleTextBox(text, placeholder, filter, renderer));
    }

    @Override
    public <T> WDropdown<T> dropdown(T[] values, T value) {
        return w(new WMonocleDropdown<>(values, value));
    }

    @Override
    public WTriangle triangle() {
        return w(new WMonocleTriangle());
    }

    @Override
    public WTooltip tooltip(String text) {
        return w(new WMonocleTooltip(text));
    }

    @Override
    public WView view() {
        return w(new WMonocleView());
    }

    @Override
    public WSection section(String title, boolean expanded, WWidget headerWidget) {
        return w(new WMonocleSection(title, expanded, headerWidget));
    }

    @Override
    public WAccount account(WidgetScreen screen, Account<?> account) {
        return w(new WMonocleAccount(screen, account));
    }

    @Override
    public WWidget module(Module module, String title) {
        return w(new WMonocleModule(module, title));
    }

    @Override
    public WQuad quad(Color color) {
        return w(new WMonocleQuad(color));
    }

    @Override
    public WTopBar topBar() {
        return w(new WMonocleTopBar());
    }

    @Override
    public WFavorite favorite(boolean checked) {
        return w(new WMonocleFavorite(checked));
    }

    // Colors

    @Override
    public Color textColor() {
        return textColor.get();
    }

    @Override
    public Color textSecondaryColor() {
        return textSecondaryColor.get();
    }

    //     Starscript

    @Override
    public Color starscriptTextColor() {
        return starscriptText.get();
    }

    @Override
    public Color starscriptBraceColor() {
        return starscriptBraces.get();
    }

    @Override
    public Color starscriptParenthesisColor() {
        return starscriptParenthesis.get();
    }

    @Override
    public Color starscriptDotColor() {
        return starscriptDots.get();
    }

    @Override
    public Color starscriptCommaColor() {
        return starscriptCommas.get();
    }

    @Override
    public Color starscriptOperatorColor() {
        return starscriptOperators.get();
    }

    @Override
    public Color starscriptStringColor() {
        return starscriptStrings.get();
    }

    @Override
    public Color starscriptNumberColor() {
        return starscriptNumbers.get();
    }

    @Override
    public Color starscriptKeywordColor() {
        return starscriptKeywords.get();
    }

    @Override
    public Color starscriptAccessedObjectColor() {
        return starscriptAccessedObjects.get();
    }

    // Other

    @Override
    public TextRenderer textRenderer() {
        return TextRenderer.get();
    }

    @Override
    public double scale(double value) {
        double scaled = value * scale.get();

        if (MacosUtil.IS_MACOS) {
            scaled /= (double) mc.getWindow().getWidth() / mc.getWindow().getWidth();
        }

        return scaled;
    }

    @Override
    public boolean categoryIcons() {
        return categoryIcons.get();
    }

    @Override
    public boolean modulesHelpText() {
        return modulesHelpText.get();
    }

    @Override
    public boolean hideHUD() {
        return hideHUD.get();
    }

    public class ThreeStateColorSetting {
        private final Setting<SettingColor> normal, hovered, pressed;

        public ThreeStateColorSetting(SettingGroup group, String name, SettingColor c1, SettingColor c2, SettingColor c3) {
            normal = color(group, name, "Color of " + name + ".", c1);
            hovered = color(group, "hovered-" + name, "Color of " + name + " when hovered.", c2);
            pressed = color(group, "pressed-" + name, "Color of " + name + " when pressed.", c3);
        }

        public SettingColor get() {
            return normal.get();
        }

        public SettingColor get(boolean pressed, boolean hovered, boolean bypassDisableHoverColor) {
            if (pressed) return this.pressed.get();
            return (hovered && (bypassDisableHoverColor || !disableHoverColor)) ? this.hovered.get() : this.normal.get();
        }

        public SettingColor get(boolean pressed, boolean hovered) {
            return get(pressed, hovered, false);
        }
    }
}
