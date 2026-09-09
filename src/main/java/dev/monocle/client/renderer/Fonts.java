/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.renderer;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.monocle.CustomFontChangedEvent;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.renderer.text.CustomTextRenderer;
import dev.monocle.client.renderer.text.FontFace;
import dev.monocle.client.renderer.text.FontFamily;
import dev.monocle.client.renderer.text.FontInfo;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.utils.PreInit;
import dev.monocle.client.utils.render.FontUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.monocle.client.MonocleClient.mc;

public class Fonts {
    public static final String DEFAULT_BUILTIN_FONT = "GlacialIndifference-Regular";
    public static final String[] BUILTIN_FONTS = {DEFAULT_BUILTIN_FONT, "GlacialIndifference-Bold", "JetBrains Mono", "Comfortaa", "Tw Cen MT", "Pixelation"};

    public static String DEFAULT_FONT_FAMILY;
    public static FontFace DEFAULT_FONT;

    public static final List<FontFamily> FONT_FAMILIES = new ArrayList<>();
    public static CustomTextRenderer RENDERER;

    private Fonts() {
    }

    @PreInit
    public static void refresh() {
        FONT_FAMILIES.clear();

        for (String builtinFont : BUILTIN_FONTS) {
            FontUtils.loadBuiltin(FONT_FAMILIES, builtinFont);
        }

        for (String fontPath : FontUtils.getSearchPaths()) {
            FontUtils.loadSystem(FONT_FAMILIES, new File(fontPath));
        }

        FONT_FAMILIES.sort(Comparator.comparing(FontFamily::getName));

        MonocleClient.LOG.info("Found {} font families.", FONT_FAMILIES.size());

        DEFAULT_FONT_FAMILY = FontUtils.getBuiltinFontInfo(DEFAULT_BUILTIN_FONT).family();
        DEFAULT_FONT = getFamily(DEFAULT_FONT_FAMILY).get(FontInfo.Type.Regular);

        Config config = Config.get();
        load(config != null ? config.font.get() : DEFAULT_FONT);
    }

    public static void load(FontFace fontFace) {
        if (RENDERER != null) {
            if (RENDERER.fontFace.equals(fontFace)) return;
            else RENDERER.destroy();
        }

        try {
            RENDERER = new CustomTextRenderer(fontFace);
            MonocleClient.EVENT_BUS.post(CustomFontChangedEvent.get());
        } catch (Exception e) {
            if (fontFace.equals(DEFAULT_FONT)) {
                throw new RuntimeException("Failed to load default font: " + fontFace, e);
            }

            MonocleClient.LOG.error("Failed to load font: {}", fontFace, e);
            load(Fonts.DEFAULT_FONT);
        }

        if (mc.gui.screen() instanceof WidgetScreen widgetScreen && Config.get().customFont.get()) {
            widgetScreen.invalidate();
        }
    }

    public static FontFamily getFamily(String name) {
        for (FontFamily fontFamily : Fonts.FONT_FAMILIES) {
            if (fontFamily.getName().equalsIgnoreCase(name)) {
                return fontFamily;
            }
        }

        return null;
    }
}
