package ru.warland.economy;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.warland.data.Store;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.economy.MarketRepository.*;

/** Deterministic queue barriers: no sleeps, production accounts, Minecraft or live trading. */
@Timeout(20)
class MarketAdmissionTest {
    @TempDir Path dir;
    Store store;
    MarketRepository market;
    final UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();
    final AtomicLong clock = new AtomicLong(1000);
    final AtomicBoolean live = new AtomicBoolean(true);
    UUID held, offered, listing;
    enum Action { DEPOSIT, DELIVERY, LIST, BUY, CANCEL }

    @BeforeEach void open() throws Exception {
        store = new Store(dir.resolve("admission.db")); store.start().get();
        market = new MarketRepository(store); market.start().get();
        held = deposit(); offered = deposit(); listing = UUID.randomUUID();
        market.list(listing, seller, offered, 100, 300, 2000, live::get, clock::get).get();
        assertTrue(store.money(buyer, 1000, "synthetic-seed", "test").get());
    }
    @AfterEach void close() { if (store != null) store.close(); }
    UUID deposit() throws Exception {
        UUID item = UUID.randomUUID();
        market.prepareDeposit(item, seller, "synthetic-stack", "before", "after", live::get, clock::get).get();
        market.reconcile(item, seller, "after", clock.get()).get();
        return item;
    }
    CompletableFuture<?> act(Action action, BooleanSupplier lease, LongSupplier time) {
        return switch (action) {
            case DEPOSIT -> market.prepareDeposit(UUID.randomUUID(), seller, "synthetic-new", "before", "after", lease, time);
            case DELIVERY -> market.prepareDelivery(UUID.randomUUID(), seller, held, "before", "after", lease, time);
            case LIST -> market.list(UUID.randomUUID(), seller, held, 100, 300, 2000, lease, time);
            case BUY -> market.buy(UUID.randomUUID(), buyer, listing, 100, lease, time);
            case CANCEL -> market.cancel(listing, seller, lease, time);
        };
    }
    final class Barrier implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Void> blocker;
        Barrier() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            blocker = store.submit(c -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Queue barrier timed out");
                return null;
            });
            if (!entered.await(5, TimeUnit.SECONDS)) { release.countDown(); fail("Worker did not reach barrier"); }
        }
        public void close() throws Exception { release.countDown(); blocker.get(5, TimeUnit.SECONDS); }
    }
    Map<String, List<List<String>>> snapshot() throws Exception {
        return store.submit(c -> {
            Map<String, List<List<String>>> result = new LinkedHashMap<>();
            for (String table : List.of("market_intents", "market_items", "market_listings", "market_trades", "accounts", "ledger", "audit")) {
                List<List<String>> rows = new ArrayList<>();
                try (var statement = c.createStatement(); var r = statement.executeQuery("SELECT * FROM " + table + " ORDER BY rowid")) {
                    while (r.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(String.valueOf(r.getObject(i)));
                        rows.add(List.copyOf(row));
                    }
                }
                result.put(table, List.copyOf(rows));
            }
            return result;
        }).get(5, TimeUnit.SECONDS);
    }
    void cancelled(CompletableFuture<?> future) {
        assertThrows(CancellationException.class, future::join);
    }
    void sqlRejected(CompletableFuture<?> future) throws Exception {
        var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(SQLException.class, error.getCause());
    }

    @ParameterizedTest @EnumSource(Action.class)
    void logoutWhileQueuedCancelsEveryActorMutationWithoutAnyWrites(Action action) throws Exception {
        var before = snapshot();
        AtomicInteger clockReads = new AtomicInteger();
        CompletableFuture<?> future;
        try (var ignored = new Barrier()) {
            future = act(action, live::get, () -> { clockReads.incrementAndGet(); return clock.get(); });
            assertFalse(future.isDone());
            live.set(false);
        }
        cancelled(future);
        assertEquals(0, clockReads.get(), "Rejected lease must not enter transaction work");
        assertEquals(before, snapshot(), "No intent/item/listing/receipt/wallet/ledger/audit change");
    }
    @ParameterizedTest @EnumSource(Action.class)
    void absentLeaseOrClockIsNeverImplicitPermission(Action action) throws Exception {
        var before = snapshot();
        assertThrows(NullPointerException.class, () -> act(action, null, clock::get));
        assertThrows(NullPointerException.class, () -> act(action, live::get, null));
        assertEquals(before, snapshot());
    }
    @ParameterizedTest @EnumSource(Action.class)
    void clockIsSampledOnceOnWorkerAfterQueueNotOnCallingThread(Action action) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        CompletableFuture<?> future;
        try (var ignored = new Barrier()) {
            future = act(action, live::get, () -> {
                assertEquals("warland-sqlite", Thread.currentThread().getName());
                reads.incrementAndGet(); return clock.get();
            });
            assertEquals(0, reads.get());
            clock.set(1500);
        }
        future.get(5, TimeUnit.SECONDS);
        assertEquals(1, reads.get());
        assertEquals(1500L, store.submit(c -> Store.scalar(c, "SELECT created FROM audit ORDER BY id DESC LIMIT 1")).get());
    }
    @ParameterizedTest @ValueSource(longs = {2000, 2001})
    void buyExpiresWhileQueuedWithoutMoneyOrOwnershipChanges(long executedAt) throws Exception {
        var before = snapshot(); CompletableFuture<?> future;
        try (var ignored = new Barrier()) {
            future = act(Action.BUY, live::get, clock::get);
            clock.set(executedAt);
        }
        sqlRejected(future); assertEquals(before, snapshot());
    }
    @Test void newListingCannotBeCreatedAlreadyExpiredAfterQueueDelay() throws Exception {
        var before = snapshot(); CompletableFuture<?> future;
        try (var ignored = new Barrier()) {
            future = act(Action.LIST, live::get, clock::get); clock.set(2000);
        }
        sqlRejected(future); assertEquals(before, snapshot());
    }
    @Test void cancellationUsesExecutionTimeForExpiredStatus() throws Exception {
        CompletableFuture<?> future;
        try (var ignored = new Barrier()) {
            future = act(Action.CANCEL, live::get, clock::get); clock.set(2000);
        }
        future.get(5, TimeUnit.SECONDS);
        assertEquals(ListingStatus.EXPIRED, market.listing(listing).get().status());
        assertEquals(ItemStatus.HELD, market.item(offered).get().status());
    }
    @Test void reconnectDoesNotResurrectOldLeaseButNewGenerationCanBuyOnce() throws Exception {
        AtomicInteger generation = new AtomicInteger(1);
        CompletableFuture<?> old, current;
        try (var ignored = new Barrier()) {
            old = act(Action.BUY, () -> generation.get() == 1, clock::get);
            generation.set(2);
            current = act(Action.BUY, () -> generation.get() == 2, clock::get);
        }
        cancelled(old); current.get(5, TimeUnit.SECONDS);
        assertEquals(1L, store.submit(c -> Store.scalar(c, "SELECT COUNT(*) FROM market_trades")).get());
        assertEquals(900L, store.submit(c -> Store.scalar(c, "SELECT balance FROM accounts WHERE owner=?", Store.player(buyer))).get());
        assertEquals(buyer, market.item(offered).get().owner());
    }
    @Test void receiptReplayRequiresLeaseButValidReplayIsNotAnotherPurchase() throws Exception {
        UUID operation = UUID.randomUUID();
        var receipt = market.buy(operation, buyer, listing, 100, live::get, clock::get).get();
        var before = snapshot(); live.set(false); clock.set(3000);
        cancelled(market.buy(operation, buyer, listing, 100, live::get, clock::get));
        AtomicBoolean newSession = new AtomicBoolean(true);
        assertEquals(receipt, market.buy(operation, buyer, listing, 100, newSession::get, clock::get).get());
        assertEquals(before, snapshot());
    }
    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidExecutionClockFailsBeforeAnyWrites(long value) throws Exception {
        var before = snapshot(); clock.set(value);
        sqlRejected(act(Action.DEPOSIT, live::get, clock::get));
        assertEquals(before, snapshot());
    }
    @Test void failedClockDoesNotLeaveTransactionOpen() throws Exception {
        var before = snapshot();
        assertThrows(ExecutionException.class, () -> act(Action.BUY, live::get, () -> { throw new IllegalStateException("clock unavailable"); }).get());
        assertEquals(before, snapshot());
        act(Action.BUY, live::get, clock::get).get();
        assertEquals(ListingStatus.SOLD, market.listing(listing).get().status());
    }
    @Test void recoveryAfterDisconnectStillReconcilesAlreadyDurableInventoryEvidence() throws Exception {
        UUID operation = UUID.randomUUID();
        market.prepareDelivery(operation, seller, held, "before", "after", live::get, clock::get).get();
        live.set(false);
        assertEquals(IntentStatus.COMMITTED, market.reconcile(operation, seller, "after", 1001).get().status());
        assertEquals(ItemStatus.DELIVERED, market.item(held).get().status());
        assertFalse(market.inventoryBlocked(seller).get());
        market.reconcile(operation, seller, "after", 1002).get();
        assertEquals(ItemStatus.DELIVERED, market.item(held).get().status());
    }
}
