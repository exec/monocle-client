import dev.monocle.client.utils.network.IrcConnection;
import java.net.URI;
import java.util.UUID;

/** Opt-in, bounded LAN test; uses an isolated channel, never #monocle. */
class IrcFloodCheck {
    public static void main(String[] args) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        URI endpoint = URI.create("ws://10.0.0.2:8080/irc");
        try (var sender = new IrcConnection(endpoint, "FloodA" + id, "#check" + id);
             var receiver = new IrcConnection(endpoint, "FloodB" + id, "#check" + id)) {
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!sender.joined() || !receiver.joined()) {
                if (System.nanoTime() > deadline) throw new AssertionError("Join timed out");
                Thread.sleep(50);
            }
            // Exceed the server's burst allowance, bypassing the game UI throttle.
            for (int i = 0; i < 12; i++) sender.say("flood-check-" + id + "-" + i);
            int received = 0;
            long first = 0, last = 0;
            while (received < 12 && System.nanoTime() < deadline) {
                String line = receiver.poll();
                if (line != null && line.contains("flood-check-" + id + "-")) {
                    last = System.nanoTime();
                    if (received++ == 0) first = last;
                } else Thread.sleep(20);
            }
            double seconds = (last - first) / 1_000_000_000.0;
            if (received != 12 || seconds < 7) {
                throw new AssertionError("Expected throttled delivery: " + received + " messages in " + seconds + "s");
            }
            System.out.printf("Server flood control passed: 12 messages delivered over %.2fs.%n", seconds);
        }
    }
}
