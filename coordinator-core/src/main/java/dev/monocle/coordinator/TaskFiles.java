package dev.monocle.coordinator;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Shared bounded UTF-8 encoding and atomic, forced task journals. */
public final class TaskFiles {
    public static final int MAX_PACKAGE = 4 * 1024 * 1024, CHUNK = 2000, MAX_JOURNAL = 64 * 1024 * 1024;
    private TaskFiles() { }
    public static JsonObject read(Path file) {
        if (!Files.exists(file)) return new JsonObject();
        try {
            if (Files.size(file) > MAX_JOURNAL) throw new IllegalArgumentException("Task journal is too large");
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Cannot read " + file + "; original left untouched: " + e.getMessage(), e); }
    }
    public static void write(Path file, JsonObject root) {
        byte[] data = jsonBytes(root, MAX_JOURNAL);
        Path temporary = null;
        try {
            // Compare the actual journal, not a remembered success: deleted or replaced files
            // must still be repaired before acknowledging a checkpoint. Changed intents keep fsync.
            if (Files.isRegularFile(file) && Files.size(file) == data.length
                && Arrays.equals(Files.readAllBytes(file), data)) return;
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "bot-task-", ".tmp");
            Files.write(temporary, data);
            try (FileChannel checkpoint = FileChannel.open(temporary, StandardOpenOption.WRITE)) { checkpoint.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new IllegalStateException("Task checkpoint failed; no new action was sent: " + e.getMessage(), e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static byte[] jsonBytes(JsonObject input, int limit) {
        if (input == null) throw new IllegalArgumentException("Missing JSON object");
        try {
            String text = input.toString();
            if (text.length() > limit) throw new IllegalArgumentException("JSON exceeds its byte limit");
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
            if (bytes.remaining() > limit) throw new IllegalArgumentException("JSON exceeds its byte limit");
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException | StackOverflowError e) { throw new IllegalArgumentException("Invalid or excessively nested JSON", e); }
    }

}
