package dev.monocle.client.utils.network;

import java.net.URI;
import java.util.UUID;

/** Optional first argument runs a real two-client LAN integration check. */
public final class IrcConnectionTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        IrcConnection.validate(URI.create("ws://10.0.0.2:8080/irc"), "Tester", "#monocle");
        IrcConnection.validate(URI.create("wss://chat.example.org/irc"), "Tester", "#monocle");
        for (String endpoint : new String[] {"http://10.0.0.2", "ws://example.org", "ws://8.8.8.8", "wss://user:pass@example.org", "wss://example.org/?token=secret"}) {
            try { IrcConnection.validate(URI.create(endpoint), "Tester", "#monocle"); throw new AssertionError(endpoint); }
            catch (IllegalArgumentException expected) { }
        }
        for (String text : new String[] {"", "hello\r\nOPER admin password", "a\u0000b", "§khidden", "a".repeat(351), "界".repeat(117)}) {
            try { IrcConnection.message(text); throw new AssertionError("Accepted invalid text"); }
            catch (IllegalArgumentException expected) { }
        }
        assert IrcConnection.message("hello world").equals("hello world");
        assert IrcConnection.plain("a\u0001b§c\u202Ed").equals("abcd");
        assert IrcConnection.plain("x".repeat(10000)).length() == 600;
        if (args.length > 0) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            try (var a = new IrcConnection(URI.create(args[0]), "TestA" + id, "#monocle");
                 var b = new IrcConnection(URI.create(args[0]), "TestB" + id, "#monocle")) {
                long end = System.nanoTime() + 20_000_000_000L;
                while ((!a.joined() || !b.joined()) && System.nanoTime() < end) Thread.sleep(50);
                if (!a.joined() || !b.joined()) throw new AssertionError(a.status() + " / " + b.status());
                String payload = "Monocle WebSocket check " + id;
                a.say(payload);
                boolean received = false;
                while (!received && System.nanoTime() < end) {
                    String line = b.poll();
                    if (line != null && line.contains(payload)) received = true;
                    else Thread.sleep(50);
                }
                assert received : "Message must cross proxy and Ergo to the second client";
                b.say("reply " + id);
                received = false;
                while (!received && System.nanoTime() < end) {
                    String line = a.poll();
                    if (line != null && line.contains("reply " + id)) received = true;
                    else Thread.sleep(50);
                }
                assert received : "Bidirectional delivery";
                a.close();
                assert !a.joined();
                System.out.println("LAN integration passed: two clients registered, joined, exchanged messages through proxy, disconnected.");
            }
        }
        System.out.println("IRC checks passed: endpoint constraints, command-injection rejection, UTF-8 limits and plain-text sanitization.");
    }
}
