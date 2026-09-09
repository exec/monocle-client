package dev.monocle.client.renderer;

import dev.monocle.client.renderer.text.BuiltinFontFace;
import dev.monocle.client.renderer.text.FontInfo;
import dev.monocle.client.utils.render.FontUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.stb.STBTruetype;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Run with ./gradlew monocleFontCheck. Uses the real native font parser/rasterizer without a GPU. */
public final class MonocleFontTest {
    public static void main(String[] args) throws Exception {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) throw new IllegalStateException("Run with assertions enabled (-ea).");

        assert FontUtils.getFontInfo(new ByteArrayInputStream(new byte[4])) == null;
        assert FontUtils.getFontInfo(new ByteArrayInputStream(new byte[12])) == null;
        assert FontUtils.getBuiltinFontInfo("missing-font") == null;
        for (String builtin : Fonts.BUILTIN_FONTS) {
            assert FontUtils.getBuiltinFontInfo(builtin) != null : "Existing TTF fonts must still load: " + builtin;
            FontUtils.loadBuiltin(Fonts.FONT_FAMILIES, builtin);
        }
        var glacial = Fonts.getFamily("Glacial Indifference");
        assert glacial != null && glacial.hasType(FontInfo.Type.Regular) && glacial.hasType(FontInfo.Type.Bold);
        assert Fonts.getFamily("Comfortaa") != null : "Previous selections remain available.";
        assert FontUtils.getBuiltinFontInfo(Fonts.DEFAULT_BUILTIN_FONT).equals(new FontInfo("Glacial Indifference", FontInfo.Type.Regular));
        checkFont("GlacialIndifference-Regular", FontInfo.Type.Regular, "de1eddf3f49fbc0782a1008a87053207f69174a3860f7068520ee4cd901ef926");
        checkFont("GlacialIndifference-Bold", FontInfo.Type.Bold, "b9007828ce438fea70e32d65fc93fe4d5fe1aead53fdb8d51f01e260db385e26");

        try (var license = MonocleFontTest.class.getResourceAsStream("/assets/monocle-client/fonts/GlacialIndifference-OFL.txt");
             var notice = MonocleFontTest.class.getResourceAsStream("/assets/monocle-client/fonts/GlacialIndifference-NOTICE.txt")) {
            assert license != null && notice != null : "Redistributed fonts need their license and copyright notices.";
            assert new String(license.readAllBytes(), StandardCharsets.UTF_8).contains("SIL OPEN FONT LICENSE Version 1.1");
            String copyright = new String(notice.readAllBytes(), StandardCharsets.UTF_8);
            assert copyright.contains("Copyright (c) 2015, Alfredo Marco Pradil");
            assert copyright.contains("with Reserved Font Name Glacial Indifference.");
        }

        var directory = Files.createTempDirectory("monocle-font-check-");
        var file = directory.resolve("Glacial.OTF");
        try {
            try (var font = FontUtils.builtinFontStream("GlacialIndifference-Bold")) {
                assert font != null;
                Files.copy(font, file);
            }
            FontUtils.loadSystem(Fonts.FONT_FAMILIES, directory.toFile());
            assert glacial.get(FontInfo.Type.Bold) instanceof BuiltinFontFace : "System discovery must not replace a bundled face.";
            Fonts.FONT_FAMILIES.clear();
            FontUtils.loadSystem(Fonts.FONT_FAMILIES, directory.toFile());
            assert Fonts.getFamily("Glacial Indifference").hasType(FontInfo.Type.Bold) : "System .otf discovery is case-insensitive.";
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
            Fonts.FONT_FAMILIES.clear();
        }
        System.out.println("Monocle font checks passed: default Regular, selectable Bold, native OpenType rasterization, legacy TTF fonts, system discovery, unmodified font bytes, and bundled license notices.");
    }

    private static void checkFont(String name, FontInfo.Type type, String sha256) throws Exception {
        FontInfo info = FontUtils.getBuiltinFontInfo(name);
        assert info != null && info.family().equals("Glacial Indifference") && info.type() == type;
        ByteBuffer data = new BuiltinFontFace(info, name).readToDirectByteBuffer();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(data.duplicate());
        assert HexFormat.of().formatHex(digest.digest()).equals(sha256) : "Keep the supplied OFL fonts unmodified.";

        ByteBuffer bitmap = BufferUtils.createByteBuffer(2048 * 2048);
        STBTTPackContext context = STBTTPackContext.create();
        STBTTPackedchar.Buffer glyphs = STBTTPackedchar.create(95);
        assert STBTruetype.stbtt_PackBegin(context, bitmap, 2048, 2048, 0, 1);
        try {
            assert STBTruetype.stbtt_PackFontRange(context, data, 0, 81, 32, glyphs) : "The UI rasterizer must support the unconverted CFF font.";
            var letter = glyphs.get('M' - 32);
            assert letter.xadvance() > 0 && letter.x1() > letter.x0() && letter.y1() > letter.y0();
            boolean ink = false;
            for (int i = 0; i < bitmap.limit(); i++) ink |= bitmap.get(i) != 0;
            assert ink : "Successful parsing must produce visible glyphs.";
        } finally {
            STBTruetype.stbtt_PackEnd(context);
        }
    }
}
