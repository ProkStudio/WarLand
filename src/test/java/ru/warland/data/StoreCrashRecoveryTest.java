package ru.warland.data;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Separate JVMs halt without close/checkpoint; only synthetic temporary databases are used. */
@Timeout(30)
class StoreCrashRecoveryTest {
    @TempDir Path dir;

    @Test void processDeathBeforeCommitRollsBackDebit() throws Exception { recover(false); }
    @Test void processDeathAfterCommitPreservesDebitAndReplayProtection() throws Exception { recover(true); }

    private void recover(boolean committed) throws Exception {
        Path database = dir.resolve("crash.db");
        Path log = dir.resolve("child.log");
        UUID player = UUID.randomUUID();
        Set<String> entries = new LinkedHashSet<>();
        for (Class<?> type : List.of(CrashWriter.class, Store.class, org.sqlite.JDBC.class, org.slf4j.LoggerFactory.class))
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m",
                "-cp", String.join(File.pathSeparator, entries), CrashWriter.class.getName(),
                database.toString(), player.toString(), committed ? "after" : "before")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Crash fixture did not exit");
            assertEquals(committed ? 32 : 31, child.exitValue(), () -> readLog(log));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertTrue(Files.exists(Path.of(database + "-wal")), "Expected an uncheckpointed WAL");
        try (Store recovered = new Store(database)) {
            recovered.start().get(5, TimeUnit.SECONDS);
            assertEquals(committed ? 40 : 100, recovered.balance(player).get(5, TimeUnit.SECONDS));
            assertEquals(committed ? 1 : 0, recovered.submit(c -> Store.scalar(c,
                    "SELECT COUNT(*) FROM ledger WHERE owner=? AND operation='spend'", Store.player(player)))
                    .get(5, TimeUnit.SECONDS));
            assertTrue(recovered.money(player, -60, "spend", "test").get(5, TimeUnit.SECONDS));
            assertEquals(40, recovered.balance(player).get(5, TimeUnit.SECONDS));
            assertEquals(1, recovered.submit(c -> Store.scalar(c,
                    "SELECT COUNT(*) FROM ledger WHERE owner=? AND operation='spend'", Store.player(player)))
                    .get(5, TimeUnit.SECONDS));
        }
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); }
        catch (Exception e) { return "Cannot read fixture log: " + e; }
    }

    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            Store store = new Store(Path.of(args[0]));
            UUID player = UUID.fromString(args[1]);
            store.start().get(5, TimeUnit.SECONDS);
            store.money(player, 100, "seed", "test").get(5, TimeUnit.SECONDS);
            if (args[2].equals("before")) {
                store.tx(c -> {
                    Store.change(c, Store.player(player), -60, "spend", "test");
                    Runtime.getRuntime().halt(31);
                    return null;
                }).get(5, TimeUnit.SECONDS);
            } else {
                store.money(player, -60, "spend", "test").get(5, TimeUnit.SECONDS);
                Runtime.getRuntime().halt(32);
            }
            throw new AssertionError("Crash fixture must not shut down gracefully");
        }
    }
}
