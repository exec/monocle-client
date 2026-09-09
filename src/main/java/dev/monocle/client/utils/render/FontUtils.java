/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */
package dev.monocle.client.utils.render;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.renderer.Fonts;
import dev.monocle.client.renderer.text.*;
import dev.monocle.client.utils.files.ByteBufferUtils;
import net.minecraft.util.Util;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@NullMarked
public final class FontUtils {
    private FontUtils() {
    }

    public static @Nullable FontInfo getSysFontInfo(File file) {
        return getFontInfo(file);
    }

    public static @Nullable FontInfo getBuiltinFontInfo(String builtin) {
        return getFontInfo(builtinFontStream(builtin));
    }

    /**
     * System font path: avoid heap byte[] by reading the file into a direct buffer.
     */
    private static @Nullable FontInfo getFontInfo(@Nullable File file) {
        if (file == null || !file.isFile()) return null;

        try {
            return getFontInfo(ByteBufferUtils.readFully(file.toPath(), BufferUtils::createByteBuffer));
        } catch (Exception e) {
            MonocleClient.LOG.warn("Failed to read font file: {}", file, e);
            return null;
        }
    }

    /**
     * Builtin/resource path: stream into a direct buffer (no byte[] intermediate).
     */
    public static @Nullable FontInfo getFontInfo(@Nullable InputStream stream) {
        if (stream == null) return null;

        try (ReadableByteChannel ch = Channels.newChannel(stream)) {
            ByteBuffer buf = ByteBufferUtils.readFully(ch, BufferUtils::createByteBuffer);
            return getFontInfo(buf);
        } catch (Exception e) {
            MonocleClient.LOG.warn("Failed to read font stream.", e);
            return null;
        }
    }

    /** Read TrueType or CFF OpenType using the same native parser as the renderer. */
    private static @Nullable FontInfo getFontInfo(ByteBuffer buffer) {
        if (buffer.remaining() < 12) return null;
        int signature = buffer.duplicate().order(ByteOrder.BIG_ENDIAN).getInt();
        if (signature != 0x00010000 && signature != 0x4F54544F) return null; // TrueType / OTTO

        STBTTFontinfo fontInfo = STBTTFontinfo.create();
        if (!STBTruetype.stbtt_InitFont(fontInfo, buffer)) return null;

        ByteBuffer nameBuffer = STBTruetype.stbtt_GetFontNameString(fontInfo, STBTruetype.STBTT_PLATFORM_ID_MICROSOFT, STBTruetype.STBTT_MS_EID_UNICODE_BMP, STBTruetype.STBTT_MS_LANG_ENGLISH, 1);
        ByteBuffer typeBuffer = STBTruetype.stbtt_GetFontNameString(fontInfo, STBTruetype.STBTT_PLATFORM_ID_MICROSOFT, STBTruetype.STBTT_MS_EID_UNICODE_BMP, STBTruetype.STBTT_MS_LANG_ENGLISH, 2);
        if (typeBuffer == null || nameBuffer == null) return null;

        return new FontInfo(
            StandardCharsets.UTF_16.decode(nameBuffer).toString(),
            FontInfo.Type.fromString(StandardCharsets.UTF_16.decode(typeBuffer).toString())
        );
    }

    public static Set<String> getSearchPaths() {
        Set<String> paths = new ObjectOpenHashSet<>();
        paths.add(System.getProperty("java.home") + "/lib/fonts");

        for (File dir : getUFontDirs()) {
            if (dir.exists()) paths.add(dir.getAbsolutePath());
        }

        for (File dir : getSFontDirs()) {
            if (dir.exists()) paths.add(dir.getAbsolutePath());
        }

        return paths;
    }

    public static List<File> getUFontDirs() {
        return switch (Util.getPlatform()) {
            case WINDOWS ->
                List.of(new File(System.getProperty("user.home") + "\\AppData\\Local\\Microsoft\\Windows\\Fonts"));
            case OSX -> List.of(new File(System.getProperty("user.home") + "/Library/Fonts/"));
            default ->
                List.of(new File(System.getProperty("user.home") + "/.local/share/fonts"), new File(System.getProperty("user.home") + "/.fonts"));
        };
    }

    public static List<File> getSFontDirs() {
        return switch (Util.getPlatform()) {
            case WINDOWS -> List.of(new File(System.getenv("SystemRoot") + "\\Fonts"));
            case OSX -> List.of(new File("/System/Library/Fonts/"));
            default -> List.of(new File("/usr/share/fonts/"));
        };
    }

    public static void loadBuiltin(List<FontFamily> fontList, String builtin) {
        FontInfo fontInfo = FontUtils.getBuiltinFontInfo(builtin);
        if (fontInfo == null) return;

        FontFace fontFace = new BuiltinFontFace(fontInfo, builtin);
        if (!addFont(fontList, fontFace)) {
            MonocleClient.LOG.warn("Failed to load builtin font {}", fontFace);
        }
    }

    public static void loadSystem(List<FontFamily> fontList, File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles(file -> {
            String name = file.getName().toLowerCase(Locale.ROOT);
            return file.isDirectory() || file.isFile() && (name.endsWith(".ttf") || name.endsWith(".otf"));
        });
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                loadSystem(fontList, file);
                continue;
            }

            FontInfo fontInfo = FontUtils.getSysFontInfo(file);
            if (fontInfo == null) continue;

            FontFamily family = Fonts.getFamily(fontInfo.family());
            if (family != null && family.get(fontInfo.type()) instanceof BuiltinFontFace) continue;

            FontFace fontFace = new SystemFontFace(fontInfo, file.toPath());
            if (!addFont(fontList, fontFace)) {
                MonocleClient.LOG.warn("Failed to load system font {}", fontFace);
            }
        }
    }

    private static boolean addFont(List<FontFamily> fontList, @Nullable FontFace font) {
        if (font == null) return false;

        FontInfo info = font.info;

        FontFamily family = Fonts.getFamily(info.family());
        if (family == null) {
            family = new FontFamily(info.family());
            fontList.add(family);
        }

        if (family.hasType(info.type())) return false;

        return family.addFont(font);
    }

    public static @Nullable InputStream builtinFontStream(String name) {
        String path = "/assets/" + MonocleClient.MOD_ID + "/fonts/" + name;
        InputStream stream = FontUtils.class.getResourceAsStream(path + ".ttf");
        return stream != null ? stream : FontUtils.class.getResourceAsStream(path + ".otf");
    }

}
