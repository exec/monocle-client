package dev.monocle.coordinator;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Native worker framing and queue budgets, shared by TCP and WebSocket. */
public final class CrewFrames {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_QUEUED_BYTES = 1024 * 1024;
    private CrewFrames() { }

    public static byte[] encode(String frame) {
        if (frame.length() > MAX_BYTES) throw new IllegalArgumentException("Workers frame exceeds 256 KiB UTF-8");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(frame));
            if (encoded.remaining() > MAX_BYTES) throw new IllegalArgumentException("Workers frame exceeds 256 KiB UTF-8");
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid Workers UTF-8 text", e); }
    }

    public static String read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > MAX_BYTES)
            throw new IOException("Invalid Workers frame length; update both host and workers to native protocol 7 (0.13.15+)");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    public static void write(DataOutputStream out, String frame) throws IOException {
        byte[] bytes = encode(frame);
        if (bytes.length == 0) throw new IOException("Empty Workers frame");
        out.writeInt(bytes.length); out.write(bytes);
    }

    /** A frame-count cap alone would allow 32 MiB of text per queue. */
    public static final class Queue {
        private record Entry(String text, int bytes) { }
        private final ArrayBlockingQueue<Entry> entries = new ArrayBlockingQueue<>(128);
        private int bytes;
        public synchronized boolean offer(String frame) {
            int size = encode(frame).length;
            if (size > MAX_QUEUED_BYTES - bytes || !entries.offer(new Entry(frame, size))) return false;
            bytes += size; return true;
        }
        private synchronized String release(Entry entry) {
            if (entry == null) return null;
            bytes -= entry.bytes(); return entry.text();
        }
        public String poll() { return release(entries.poll()); }
        public String take() throws InterruptedException { return release(entries.take()); }
        public String poll(long timeout, TimeUnit unit) throws InterruptedException { return release(entries.poll(timeout, unit)); }
        public void clear() { while (poll() != null) { } }
    }
}
