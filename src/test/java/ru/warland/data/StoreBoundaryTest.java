package ru.warland.data;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class StoreBoundaryTest {
    private static final long LIMIT = 1_000_000_000_000L;
    @TempDir Path dir;
    private Store store;
    private final UUID player = UUID.randomUUID();

    @BeforeEach void open() throws Exception {
        store = new Store(dir.resolve("store.db"));
        await(store.start());
    }

    @AfterEach void close() { if (store != null) store.close(); }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private long scalar(String sql, Object... params) throws Exception {
        return await(store.submit(c -> Store.scalar(c, sql, params)));
    }

    private String string(String sql, Object... params) throws Exception {
        return await(store.submit(c -> Store.string(c, sql, params)));
    }

    private void rejectsSql(CompletableFuture<?> future) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(future));
        assertInstanceOf(SQLException.class, error.getCause());
    }

    @Test void rejectsOutOfRangeDeltasBeforeCreatingAccounts() throws Exception {
        for (long delta : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -LIMIT - 1, LIMIT + 1})
            rejectsSql(store.money(player, delta, "invalid-" + delta, "test"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM accounts"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void acceptsInclusiveMoneyBoundsButNeverExceedsBalanceCap() throws Exception {
        assertTrue(await(store.money(player, LIMIT, "max", "test")));
        assertFalse(await(store.money(player, 1, "overflow", "test")));
        assertEquals(LIMIT, await(store.balance(player)));
        assertTrue(await(store.money(player, -LIMIT, "min", "test")));
        assertFalse(await(store.money(player, -1, "overdraft", "test")));
        assertEquals(0, await(store.balance(player)));
        assertEquals(2, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void idempotencyIsScopedByOwnerAndRejectsConflictingDelta() throws Exception {
        UUID other = UUID.randomUUID();
        assertTrue(await(store.money(player, 10, "same", "test")));
        assertTrue(await(store.money(other, 10, "same", "test")));
        assertTrue(await(store.money(player, 10, "same", "test")));
        rejectsSql(store.money(player, -10, "same", "test"));
        assertEquals(10, await(store.balance(player)));
        assertEquals(10, await(store.balance(other)));
        assertEquals(2, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void rejectsMissingOrOversizedOperationIds() throws Exception {
        for (String operation : new String[]{null, "", " ", "x".repeat(181)})
            rejectsSql(store.money(player, 1, operation, "test"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM accounts"));
        assertTrue(await(store.money(player, 1, "x".repeat(180), "test")));
    }

    @Test void zeroDeltaIsReplaySafe() throws Exception {
        assertTrue(await(store.money(player, 0, "zero", "test")));
        assertTrue(await(store.money(player, 0, "zero", "test")));
        assertEquals(0, await(store.balance(player)));
        assertEquals(1, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void rollbackCoversBothAccountsAndLedgerAfterPartialTransfer() throws Exception {
        UUID other = UUID.randomUUID();
        await(store.money(player, 100, "seed", "test"));
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(store.tx(c -> {
            assertTrue(Store.change(c, Store.player(player), -60, "transfer", "test"));
            assertTrue(Store.change(c, Store.player(other), 60, "transfer", "test"));
            throw new IllegalStateException("simulated failure before commit");
        })));
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals(100, await(store.balance(player)));
        assertEquals(0, await(store.balance(other)));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger WHERE operation='transfer'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM accounts WHERE owner=?", Store.player(other)));
    }

    @Test void fatalWorkFailureDoesNotCommitTransaction() throws Exception {
        await(store.money(player, 100, "seed", "test"));
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(store.tx(c -> {
            Store.change(c, Store.player(player), -60, "fatal", "test");
            throw new AssertionError("simulated unchecked fatal failure");
        })));
        assertInstanceOf(AssertionError.class, error.getCause());
        assertEquals(100, await(store.balance(player)));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger WHERE operation='fatal'"));
        assertTrue(await(store.money(player, 1, "after-error", "test")));
        assertEquals(101, await(store.balance(player)));
    }

    @Test void rejectedSpendLeavesNoLedgerAndDoesNotPoisonWorker() throws Exception {
        assertFalse(await(store.money(player, -1, "spend", "test")));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger"));
        assertTrue(await(store.money(player, 10, "seed", "test")));
        assertTrue(await(store.money(player, -1, "spend", "test")));
        assertEquals(9, await(store.balance(player)));
    }

    @Test void ledgerRejectsUpdatesAndDeletes() throws Exception {
        await(store.money(player, 1, "seed", "test"));
        rejectsSql(store.tx(c -> Store.update(c, "UPDATE ledger SET delta=999")));
        rejectsSql(store.tx(c -> Store.update(c, "DELETE FROM ledger")));
        assertEquals(1, scalar("SELECT delta FROM ledger"));
        assertEquals(1, await(store.balance(player)));
    }

    @Test void schemaUsesWalFullSyncForeignKeysAndVersionOne() throws Exception {
        assertEquals("wal", string("PRAGMA journal_mode"));
        assertEquals(2, scalar("PRAGMA synchronous"));
        assertEquals(1, scalar("PRAGMA foreign_keys"));
        assertEquals(1, scalar("PRAGMA user_version"));
        await(store.checkpoint());
    }

    @Test void foreignKeyFailureRollsBackUnrelatedMoneyWrite() throws Exception {
        rejectsSql(store.tx(c -> {
            Store.change(c, Store.player(player), 10, "invalid-member", "test");
            return Store.update(c, "INSERT INTO members(uuid,nation,rank,joined) VALUES(?,?,'MEMBER',0)",
                    player.toString(), "missing-nation");
        }));
        assertEquals(0, await(store.balance(player)));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void restartKeepsBalanceAndLedgerReplayProtection() throws Exception {
        await(store.money(player, 75, "seed", "test"));
        store.close();
        store = new Store(dir.resolve("store.db"));
        await(store.start());
        assertEquals(75, await(store.balance(player)));
        assertTrue(await(store.money(player, 75, "seed", "test")));
        assertEquals(75, await(store.balance(player)));
        assertEquals(1, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void restartAbortsPreparedButPreservesCommittedInventoryJournal() throws Exception {
        await(store.tx(c -> {
            for (String status : List.of("PREPARED", "COMMITTED", "ABORTED"))
                Store.update(c, "INSERT INTO inventory_journal(uuid,before_json,after_json,status,operation,created) VALUES(?,'[]','[]',?,?,0)",
                        player.toString(), status, status);
            return null;
        }));
        store.close();
        store = new Store(dir.resolve("store.db"));
        await(store.start());
        assertEquals("ABORTED", string("SELECT status FROM inventory_journal WHERE operation='PREPARED'"));
        assertEquals("COMMITTED", string("SELECT status FROM inventory_journal WHERE operation='COMMITTED'"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM inventory_journal"));
    }

    @Test void unsupportedFutureSchemaFailsWithoutRewritingVersionOrData() throws Exception {
        Path futureFile = dir.resolve("future.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + futureFile);
             Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=2");
            s.execute("CREATE TABLE sentinel(value TEXT)");
            s.execute("INSERT INTO sentinel VALUES('preserved')");
        }
        try (Store future = new Store(futureFile)) { rejectsSql(future.start()); }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + futureFile)) {
            assertEquals(2, Store.scalar(c, "PRAGMA user_version"));
            assertEquals("preserved", Store.string(c, "SELECT value FROM sentinel"));
            assertEquals(0, Store.scalar(c, "SELECT COUNT(*) FROM sqlite_master WHERE name='accounts'"));
        }
    }

    @Test void closeDrainsQueuedWritesAndRejectsLaterSubmissions() throws Exception {
        List<CompletableFuture<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 25; i++) jobs.add(store.money(player, 1, "queued-" + i, "test"));
        Store old = store;
        store.close();
        store = null;
        for (CompletableFuture<Boolean> job : jobs) assertTrue(await(job));
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(old.balance(player)));
        assertInstanceOf(RejectedExecutionException.class, error.getCause());
        store = new Store(dir.resolve("store.db"));
        await(store.start());
        assertEquals(25, await(store.balance(player)));
    }

    @Test void concurrentTransfersConserveMoneyAndNeverOverdraw() throws Exception {
        UUID other = UUID.randomUUID();
        await(store.money(player, 100, "seed", "test"));
        List<CompletableFuture<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String operation = "transfer-" + i;
            jobs.add(store.tx(c -> {
                if (!Store.change(c, Store.player(player), -2, operation, "test")) return false;
                if (!Store.change(c, Store.player(other), 2, operation, "test")) throw new SQLException("recipient rejected");
                return true;
            }));
        }
        await(CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)));
        assertEquals(50, jobs.stream().filter(CompletableFuture::join).count());
        assertEquals(0, await(store.balance(player)));
        assertEquals(100, await(store.balance(other)));
        assertEquals(100, scalar("SELECT SUM(balance) FROM accounts"));
        assertEquals(101, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void sqlParametersRemainLiteralData() throws Exception {
        String value = "x'); DROP TABLE accounts; --";
        await(store.state(value, value, value));
        assertEquals(value, await(store.state(value, value)));
        await(store.money(player, 10, value, value));
        assertEquals(10, await(store.balance(player)));
        assertEquals(value, string("SELECT reason FROM ledger WHERE operation=?", value));
    }
}
