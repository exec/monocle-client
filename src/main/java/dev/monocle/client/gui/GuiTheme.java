/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui;

import dev.monocle.client.gui.renderer.packer.GuiTexture;
import dev.monocle.client.gui.screens.HighwayBuilderScreen;
import dev.monocle.client.gui.screens.InventoryManagerScreen;
import dev.monocle.client.gui.screens.ModuleScreen;
import dev.monocle.client.gui.screens.ModulesScreen;
import dev.monocle.client.gui.screens.NotebotSongsScreen;
import dev.monocle.client.gui.screens.ProxiesScreen;
import dev.monocle.client.gui.screens.accounts.AccountsScreen;
import dev.monocle.client.gui.tabs.TabScreen;
import dev.monocle.client.gui.utils.CharFilter;
import dev.monocle.client.gui.utils.SettingsWidgetFactory;
import dev.monocle.client.gui.utils.WindowConfig;
import dev.monocle.client.gui.widgets.*;
import dev.monocle.client.gui.widgets.containers.*;
import dev.monocle.client.gui.widgets.input.*;
import dev.monocle.client.gui.widgets.pressable.*;
import dev.monocle.client.renderer.Texture;
import dev.monocle.client.renderer.text.TextRenderer;
import dev.monocle.client.settings.Settings;
import dev.monocle.client.systems.accounts.Account;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.misc.InventoryTweaks;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.misc.ISerializable;
import dev.monocle.client.utils.misc.Keybind;
import dev.monocle.client.utils.misc.Names;
import dev.monocle.client.utils.render.color.Color;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public abstract class GuiTheme implements ISerializable<GuiTheme> {
    public static final double TITLE_TEXT_SCALE = 1.25;

    public final String name;
    public final Settings settings = new Settings();

    public boolean disableHoverColor;

    protected SettingsWidgetFactory settingsFactory;

    protected final Map<String, WindowConfig> windowConfigs = new HashMap<>();

    public GuiTheme(String name) {
        this.name = name;
    }

    public void beforeRender() {
        disableHoverColor = false;
    }

    // Widgets

    public abstract WWindow window(WWidget icon, String title);

    public WWindow window(String title) {
        return window(null, title);
    }

    public abstract WLabel label(String text, boolean title, double maxWidth);

    public WLabel label(String text, boolean title) {
        return label(text, title, 0);
    }

    public WLabel label(String text, double maxWidth) {
        return label(text, false, maxWidth);
    }

    public WLabel label(String text) {
        return label(text, false);
    }

    public abstract WHorizontalSeparator horizontalSeparator(String text);

    public WHorizontalSeparator horizontalSeparator() {
        return horizontalSeparator(null);
    }

    public abstract WVerticalSeparator verticalSeparator();

    protected abstract WButton button(String text, GuiTexture texture);

    public WButton button(String text) {
        return button(text, null);
    }

    public WButton button(GuiTexture texture) {
        return button(null, texture);
    }

    protected abstract WConfirmedButton confirmedButton(String text, String confirmText, GuiTexture texture);

    public WConfirmedButton confirmedButton(String text, String confirmText) {
        return confirmedButton(text, confirmText, null);
    }

    public WConfirmedButton confirmedButton(GuiTexture texture) {
        return confirmedButton(null, null, texture);
    }

    public abstract WMinus minus();

    public abstract WConfirmedMinus confirmedMinus();

    public abstract WPlus plus();

    public abstract WCheckbox checkbox(boolean checked);

    public abstract WSlider slider(double value, double min, double max);

    public abstract WTextBox textBox(String text, String placeholder, CharFilter filter, Class<? extends WTextBox.Renderer> renderer);

    public WTextBox textBox(String text, CharFilter filter, Class<? extends WTextBox.Renderer> renderer) {
        return textBox(text, null, filter, renderer);
    }

    public WTextBox textBox(String text, String placeholder, CharFilter filter) {
        return textBox(text, placeholder, filter, null);
    }

    public WTextBox textBox(String text, CharFilter filter) {
        return textBox(text, filter, null);
    }

    public WTextBox textBox(String text, String placeholder) {
        return textBox(text, placeholder, (_, _) -> true, null);
    }

    public WTextBox textBox(String text) {
        return textBox(text, (_, _) -> true, null);
    }

    public abstract <T> WDropdown<T> dropdown(T[] values, T value);

    @SuppressWarnings("unchecked")
    public <T extends Enum<?>> WDropdown<T> dropdown(T value) {
        Class<?> klass = value.getDeclaringClass();
        T[] values = (T[]) klass.getEnumConstants();
        return dropdown(values, value);
    }

    public abstract WTriangle triangle();

    public abstract WTooltip tooltip(String text);

    public abstract WView view();

    public WVerticalList verticalList() {
        return w(new WVerticalList());
    }

    public WHorizontalList horizontalList() {
        return w(new WHorizontalList());
    }

    public WTable table() {
        return w(new WTable());
    }

    public abstract WSection section(String title, boolean expanded, WWidget headerWidget);

    public WSection section(String title, boolean expanded) {
        return section(title, expanded, null);
    }

    public WSection section(String title) {
        return section(title, true);
    }

    public abstract WAccount account(WidgetScreen screen, Account<?> account);

    public WWidget module(Module module) {
        return module(module, module.title);
    }

    public abstract WWidget module(Module module, String title);

    public abstract WQuad quad(Color color);

    public abstract WTopBar topBar();

    public abstract WFavorite favorite(boolean checked);

    public WItem item(ItemStack itemStack) {
        return w(new WItem(itemStack));
    }

    public WItemWithLabel itemWithLabel(ItemStack stack, String name) {
        return w(new WItemWithLabel(stack, name));
    }

    public WItemWithLabel itemWithLabel(ItemStack stack) {
        return itemWithLabel(stack, Names.get(stack.getItem()));
    }

    public WTexture texture(double width, double height, double rotation, Texture texture) {
        return w(new WTexture(width, height, rotation, texture));
    }

    public WIntEdit intEdit(int value, int min, int max, int sliderMin, int sliderMax, boolean noSlider) {
        return w(new WIntEdit(value, min, max, sliderMin, sliderMax, noSlider));
    }

    public WIntEdit intEdit(int value, int min, int max, int sliderMin, int sliderMax) {
        return w(new WIntEdit(value, min, max, sliderMin, sliderMax, false));
    }

    public WIntEdit intEdit(int value, int min, int max, boolean noSlider) {
        return w(new WIntEdit(value, min, max, 0, 0, noSlider));
    }

    public WDoubleEdit doubleEdit(double value, double min, double max, double sliderMin, double sliderMax, int decimalPlaces, boolean noSlider) {
        return w(new WDoubleEdit(value, min, max, sliderMin, sliderMax, decimalPlaces, noSlider));
    }

    public WDoubleEdit doubleEdit(double value, double min, double max, double sliderMin, double sliderMax) {
        return w(new WDoubleEdit(value, min, max, sliderMin, sliderMax, 3, false));
    }

    public WDoubleEdit doubleEdit(double value, double min, double max) {
        return w(new WDoubleEdit(value, min, max, 0, 10, 3, false));
    }

    public WBlockPosEdit blockPosEdit(BlockPos value) {
        return w(new WBlockPosEdit(value));
    }

    public WKeybind keybind(Keybind keybind) {
        return keybind(keybind, Keybind.none());
    }

    public WKeybind keybind(Keybind keybind, Keybind defaultValue) {
        return w(new WKeybind(keybind, defaultValue));
    }

    public WWidget settings(Settings settings, String filter) {
        return settingsFactory.create(this, settings, filter);
    }

    public WWidget settings(Settings settings) {
        return settings(settings, "");
    }

    // Screens

    public TabScreen modulesScreen() {
        return new ModulesScreen(this);
    }

    public boolean isModulesScreen(Screen screen) {
        return screen instanceof ModulesScreen;
    }

    public WidgetScreen moduleScreen(Module module) {
        if (module instanceof HighwayBuilder builder) return new HighwayBuilderScreen(this, builder);
        if (module instanceof InventoryTweaks inventory) return new InventoryManagerScreen(this, inventory);
        return new ModuleScreen(this, module);
    }

    public WidgetScreen accountsScreen() {
        return new AccountsScreen(this);
    }

    public NotebotSongsScreen notebotSongs() {
        return new NotebotSongsScreen(this);
    }

    public WidgetScreen proxiesScreen() {
        return new ProxiesScreen(this);
    }

    // Colors

    public abstract Color textColor();

    public abstract Color textSecondaryColor();

    //     Starscript

    public abstract Color starscriptTextColor();

    public abstract Color starscriptBraceColor();

    public abstract Color starscriptParenthesisColor();

    public abstract Color starscriptDotColor();

    public abstract Color starscriptCommaColor();

    public abstract Color starscriptOperatorColor();

    public abstract Color starscriptStringColor();

    public abstract Color starscriptNumberColor();

    public abstract Color starscriptKeywordColor();

    public abstract Color starscriptAccessedObjectColor();

    // Other

    public abstract TextRenderer textRenderer();

    public abstract double scale(double value);

    public abstract boolean categoryIcons();

    public abstract boolean modulesHelpText();

    public abstract boolean hideHUD();

    public double textWidth(String text, int length, boolean title) {
        return scale(textRenderer().getWidth(text, length, false) * (title ? TITLE_TEXT_SCALE : 1));
    }

    public double textWidth(String text) {
        return textWidth(text, text.length(), false);
    }

    public double textHeight(boolean title) {
        return scale(textRenderer().getHeight() * (title ? TITLE_TEXT_SCALE : 1));
    }

    public double textHeight() {
        return textHeight(false);
    }

    public double pad() {
        return scale(6);
    }

    public WindowConfig getWindowConfig(String id) {
        WindowConfig config = windowConfigs.get(id);
        if (config != null) return config;

        config = new WindowConfig();
        windowConfigs.put(id, config);
        return config;
    }

    public void clearWindowConfigs() {
        windowConfigs.clear();
    }

    protected <T extends WWidget> T w(T widget) {
        widget.theme = this;
        return widget;
    }

    // Saving / Loading

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();

        tag.putString("name", name);
        tag.put("settings", settings.toTag());

        CompoundTag configs = new CompoundTag();
        for (var entry : windowConfigs.entrySet()) {
            configs.put(entry.getKey(), entry.getValue().toTag());
        }
        tag.put("windowConfigs", configs);

        return tag;
    }

    @Override
    public GuiTheme fromTag(CompoundTag tag) {
        tag.getCompound("settings").ifPresent(settings::fromTag);

        tag.getCompound("windowConfigs").ifPresent(configs -> {
            for (String id : configs.keySet()) {
                windowConfigs.put(id, new WindowConfig().fromTag(configs.getCompound(id).get()));
            }
        });

        return this;
    }
}
