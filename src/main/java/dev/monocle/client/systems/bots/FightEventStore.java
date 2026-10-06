package dev.monocle.client.systems.bots;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One SQLite file per fight; all disk I/O after setup stays off the render thread. */
final class FightEventStore implements AutoCloseable {
    record Event(long timeMs, int tick, String kind, String evidence, String actorId, String actorName,
                 Double x, Double y, Double z, String data) { }

    private final Path path;
    private final Connection connection;
    private final ArrayBlockingQueue<Event> queue = new ArrayBlockingQueue<>(65_536);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean closing;
    private volatile Throwable failure;

    FightEventStore(Path path, Map<String, String> metadata) throws Exception {
        this.path = path;
        Files.createDirectories(path.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, time_ms INTEGER NOT NULL, tick INTEGER NOT NULL, kind TEXT NOT NULL, evidence TEXT NOT NULL, actor_id TEXT, actor_name TEXT, x REAL, y REAL, z REAL, data TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS events_time ON events(time_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS events_kind_time ON events(kind,time_ms)");
            statement.execute("CREATE INDEX IF NOT EXISTS events_actor_time ON events(actor_id,time_ms)");
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?)")) {
            for (var entry : metadata.entrySet()) {
                insert.setString(1, entry.getKey()); insert.setString(2, entry.getValue()); insert.addBatch();
            }
            insert.executeBatch();
        }
        writer = Thread.ofPlatform().daemon().name("Crystal fight SQLite writer").start(this::writeLoop);
    }

    Path path() { return path; }

    void append(Event event) {
        if (!closing && !queue.offer(event)) dropped.incrementAndGet();
    }

    void check() {
        if (failure != null) throw new IllegalStateException("Crystal fight logging failed: " + failure.getMessage(), failure);
    }

    private void writeLoop() {
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO events(time_ms,tick,kind,evidence,actor_id,actor_name,x,y,z,data) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            connection.setAutoCommit(false);
            while (!closing || !queue.isEmpty()) {
                Event first = queue.poll(100, TimeUnit.MILLISECONDS);
                int count = 0;
                if (first != null) { add(insert, first); count++; }
                while (count < 512 && (first = queue.poll()) != null) { add(insert, first); count++; }
                long lost = dropped.getAndSet(0);
                if (lost != 0) { add(insert, new Event(System.currentTimeMillis(), -1, "telemetry_gap", "observed", null, null,
                    null, null, null, "{\"droppedEvents\":" + lost + "}")); count++; }
                if (count != 0) { insert.executeBatch(); connection.commit(); }
            }
            try (Statement checkpoint = connection.createStatement()) { checkpoint.execute("PRAGMA wal_checkpoint(TRUNCATE)"); }
        } catch (Throwable error) { failure = error; }
        finally { try { connection.close(); } catch (SQLException error) { if (failure == null) failure = error; } }
    }

    private static void add(PreparedStatement insert, Event event) throws SQLException {
        insert.setLong(1, event.timeMs()); insert.setInt(2, event.tick()); insert.setString(3, event.kind());
        insert.setString(4, event.evidence()); insert.setString(5, event.actorId()); insert.setString(6, event.actorName());
        setCoordinate(insert, 7, event.x()); setCoordinate(insert, 8, event.y()); setCoordinate(insert, 9, event.z());
        insert.setString(10, event.data()); insert.addBatch();
    }

    private static void setCoordinate(PreparedStatement insert, int column, Double value) throws SQLException {
        if (value == null) insert.setNull(column, Types.REAL); else insert.setDouble(column, value);
    }

    @Override public void close() {
        closing = true;
        try { writer.join(2_000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        check();
    }
}
