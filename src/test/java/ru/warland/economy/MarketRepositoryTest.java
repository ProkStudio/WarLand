package ru.warland.economy;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.economy.MarketRepository.*;

@Timeout(20)
class MarketRepositoryTest {
    @TempDir Path dir;
    Store store;
    MarketRepository market;
    UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();
    static final long NOW = 1_000, END = 2_000;
    static final String STACK = "{\"id\":\"minecraft:diamond\",\"count\":3}";

    @BeforeEach void open() throws Exception {
        store = new Store(dir.resolve("market.db")); store.start().get();
        market = new MarketRepository(store); market.start().get();
    }
    @AfterEach void close() { store.close(); }
    UUID deposit(UUID owner) throws Exception {
        UUID id = UUID.randomUUID();
        market.prepareDeposit(id, owner, STACK, "before", "after", () -> true, () -> NOW).get();
        market.reconcile(id, owner, "after", NOW).get(); return id;
    }
    UUID offer(long price, int fee) throws Exception {
        UUID id = UUID.randomUUID(); market.list(id, seller, deposit(seller), price, fee, END, () -> true, () -> NOW).get(); return id;
    }
    void fund(UUID player, long amount) throws Exception { assertTrue(store.money(player, amount, UUID.randomUUID().toString(), "test").get()); }
    long count(String table) throws Exception { return store.submit(c -> Store.scalar(c, "SELECT COUNT(*) FROM " + table)).get(); }
    void reject(CompletableFuture<?> future) { assertThrows(CompletionException.class, future::join); }

    @Test void depositMustBeDurablyReconciledBeforeListing() throws Exception {
        UUID id = UUID.randomUUID(); market.prepareDeposit(id, seller, STACK, "before", "after", () -> true, () -> NOW).get();
        assertTrue(market.inventoryBlocked(seller).get()); assertEquals(0, count("market_items"));
        reject(market.list(UUID.randomUUID(), seller, id, 100, 0, END, () -> true, () -> NOW));
        assertEquals(IntentStatus.COMMITTED, market.reconcile(id, seller, "after", NOW).get().status());
        assertFalse(market.inventoryBlocked(seller).get()); assertEquals(1, market.mailbox(seller, 10).get().size());
    }
    @Test void unchangedInventoryAbortsWithoutCreatingItem() throws Exception {
        UUID id = UUID.randomUUID(); market.prepareDeposit(id, seller, STACK, "before", "after", () -> true, () -> NOW).get();
        assertEquals(IntentStatus.ABORTED, market.reconcile(id, seller, "before", NOW).get().status());
        assertEquals(0, count("market_items")); assertFalse(market.inventoryBlocked(seller).get());
    }
    @Test void unknownInventoryLocksUntilEvidenceResolves() throws Exception {
        UUID id = UUID.randomUUID(); market.prepareDeposit(id, seller, STACK, "before", "after", () -> true, () -> NOW).get();
        assertEquals(IntentStatus.RECOVERY, market.reconcile(id, seller, "unrelated", NOW).get().status());
        assertTrue(market.inventoryBlocked(seller).get());
        reject(market.prepareDeposit(UUID.randomUUID(), seller, STACK, "a", "b", () -> true, () -> NOW));
        market.reconcile(id, seller, "after", NOW).get(); assertEquals(1, count("market_items"));
    }
    @Test void startupDoesNotSilentlyAbortPendingMarketInventory() throws Exception {
        UUID id = UUID.randomUUID(); market.prepareDeposit(id, seller, STACK, "before", "after", () -> true, () -> NOW).get();
        store.close(); store = new Store(dir.resolve("market.db")); store.start().get();
        market = new MarketRepository(store); market.start().get();
        assertEquals(IntentStatus.PREPARED, market.intent(id).get().status()); assertTrue(market.inventoryBlocked(seller).get());
    }
    @Test void identicalDepositReplayAndReconciliationAreIdempotent() throws Exception {
        UUID id = UUID.randomUUID();
        var first = market.prepareDeposit(id, seller, STACK, "before", "after", () -> true, () -> NOW).get();
        assertEquals(first, market.prepareDeposit(id, seller, STACK, "before", "after", () -> true, () -> NOW + 1).get());
        market.reconcile(id, seller, "after", NOW).get(); market.reconcile(id, seller, "after", NOW).get();
        assertEquals(1, count("market_items")); assertEquals(2, count("audit"));
    }
    @Test void conflictingDepositReplayFails() throws Exception {
        UUID id = UUID.randomUUID(); market.prepareDeposit(id, seller, STACK, "a", "b", () -> true, () -> NOW).get();
        reject(market.prepareDeposit(id, buyer, STACK, "a", "b", () -> true, () -> NOW));
        reject(market.prepareDeposit(id, seller, "other-stack", "a", "b", () -> true, () -> NOW));
        reject(market.prepareDeposit(id, seller, STACK, "a", "c", () -> true, () -> NOW));
        reject(market.reconcile(id, buyer, "b", NOW));
    }
    @Test void rejectsInvalidOrUnboundedSnapshotPayloads() {
        reject(market.prepareDeposit(UUID.randomUUID(), seller, STACK, "same", "same", () -> true, () -> NOW));
        reject(market.prepareDeposit(UUID.randomUUID(), seller, " ", "a", "b", () -> true, () -> NOW));
        reject(market.prepareDeposit(UUID.randomUUID(), seller, STACK, "a".repeat(262_145), "b", () -> true, () -> NOW));
        reject(market.prepareDeposit(UUID.randomUUID(), seller, STACK, "a", "b", () -> true, () -> 0));
    }
    @Test void pendingUniquenessIsPerPlayer() throws Exception {
        market.prepareDeposit(UUID.randomUUID(), seller, STACK, "a", "b", () -> true, () -> NOW).get();
        reject(market.prepareDeposit(UUID.randomUUID(), seller, STACK, "a", "b", () -> true, () -> NOW));
        market.prepareDeposit(UUID.randomUUID(), buyer, STACK, "a", "b", () -> true, () -> NOW).get(); assertEquals(2, count("market_intents"));
    }
    @Test void deliveryIsReservedAndCannotBeListedTwice() throws Exception {
        UUID item = deposit(seller), op = UUID.randomUUID();
        var first = market.prepareDelivery(op, seller, item, "empty", "full", () -> true, () -> NOW).get();
        assertEquals(first, market.prepareDelivery(op, seller, item, "empty", "full", () -> true, () -> NOW).get());
        assertEquals(ItemStatus.DELIVERING, market.item(item).get().status());
        assertTrue(market.mailbox(seller, 10).get().isEmpty());
        reject(market.list(UUID.randomUUID(), seller, item, 100, 0, END, () -> true, () -> NOW));
        reject(market.prepareDelivery(UUID.randomUUID(), seller, item, "empty", "full", () -> true, () -> NOW));
        market.reconcile(op, seller, "full", NOW).get(); market.reconcile(op, seller, "full", NOW).get();
        assertEquals(ItemStatus.DELIVERED, market.item(item).get().status());
        reject(market.prepareDelivery(UUID.randomUUID(), seller, item, "empty", "full", () -> true, () -> NOW));
    }
    @Test void failedDeliveryReturnsExactlyOneItemToMailbox() throws Exception {
        UUID item = deposit(seller), op = UUID.randomUUID();
        market.prepareDelivery(op, seller, item, "empty", "full", () -> true, () -> NOW).get();
        market.reconcile(op, seller, "empty", NOW).get();
        assertEquals(1, market.mailbox(seller, 10).get().size());
        assertEquals(IntentStatus.ABORTED, market.prepareDelivery(op, seller, item, "empty", "full", () -> true, () -> NOW).get().status());
        assertEquals(ItemStatus.HELD, market.item(item).get().status());
        market.prepareDelivery(UUID.randomUUID(), seller, item, "empty", "full", () -> true, () -> NOW).get();
    }
    @Test void ambiguousDeliveryKeepsReservation() throws Exception {
        UUID item = deposit(seller), op = UUID.randomUUID(); market.prepareDelivery(op, seller, item, "a", "b", () -> true, () -> NOW).get();
        market.reconcile(op, seller, "c", NOW).get();
        assertEquals(ItemStatus.DELIVERING, market.item(item).get().status()); assertTrue(market.inventoryBlocked(seller).get());
        market.reconcile(op, seller, "a", NOW).get(); assertEquals(ItemStatus.HELD, market.item(item).get().status());
    }
    @Test void cannotDeliverSomeoneElsesItemOrReuseDepositAsDelivery() throws Exception {
        UUID item = deposit(seller);
        reject(market.prepareDelivery(UUID.randomUUID(), buyer, item, "a", "b", () -> true, () -> NOW));
        reject(market.prepareDelivery(item, seller, item, "before", "after", () -> true, () -> NOW));
        assertEquals(ItemStatus.HELD, market.item(item).get().status());
    }
    @Test void saleAtomicallyTransfersOwnershipAndConservesFunds() throws Exception {
        UUID listing = offer(101, 250); fund(buyer, 200);
        Trade trade = market.buy(UUID.randomUUID(), buyer, listing, 101, () -> true, () -> NOW).get();
        assertEquals(2, trade.fee()); assertEquals(99, store.balance(seller).get()); assertEquals(99, store.balance(buyer).get());
        assertEquals(2, store.submit(c -> Store.scalar(c, "SELECT balance FROM accounts WHERE owner=?", FEE_ACCOUNT)).get());
        assertEquals(buyer, market.item(market.listing(listing).get().item()).get().owner());
        assertEquals(1, market.mailbox(buyer, 10).get().size()); assertEquals(ListingStatus.SOLD, market.listing(listing).get().status());
    }
    @Test void purchaseReplayAfterExpiryDoesNotPayTwice() throws Exception {
        UUID listing = offer(100, 0), op = UUID.randomUUID(); fund(buyer, 200);
        var receipt = market.buy(op, buyer, listing, 100, () -> true, () -> NOW).get();
        assertEquals(receipt, market.buy(op, buyer, listing, 100, () -> true, () -> END + 1).get());
        assertEquals(100, store.balance(buyer).get()); assertEquals(1, count("market_trades"));
        reject(market.buy(op, buyer, listing, 99, () -> true, () -> NOW)); reject(market.buy(op, seller, listing, 100, () -> true, () -> NOW));
        reject(market.buy(op, buyer, UUID.randomUUID(), 100, () -> true, () -> NOW));
    }
    @Test void competingBuyersHaveExactlyOneWinner() throws Exception {
        UUID listing = offer(100, 0); List<CompletableFuture<Boolean>> attempts = new ArrayList<>();
        List<UUID> buyers = new ArrayList<>();
        for (int i = 0; i < 12; i++) { UUID id = UUID.randomUUID(); buyers.add(id); fund(id, 100); }
        for (UUID id : buyers) attempts.add(market.buy(UUID.randomUUID(), id, listing, 100, () -> true, () -> NOW).handle((v, e) -> e == null));
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get();
        assertEquals(1, attempts.stream().filter(CompletableFuture::join).count()); assertEquals(1, count("market_trades"));
        long balances = 0; for (UUID id : buyers) balances += store.balance(id).get();
        assertEquals(1100, balances); assertEquals(100, store.balance(seller).get());
    }
    @Test void insufficientFundsRollsBackEverything() throws Exception {
        UUID listing = offer(100, 0); fund(buyer, 99); long ledger = count("ledger");
        reject(market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> NOW));
        assertEquals(99, store.balance(buyer).get()); assertEquals(ledger, count("ledger"));
        assertEquals(ListingStatus.OPEN, market.listing(listing).get().status()); assertEquals(0, count("market_trades"));
    }
    @Test void sellerBalanceOverflowRollsBackBuyerDebit() throws Exception {
        UUID listing = offer(100, 0); fund(buyer, 100); fund(seller, MAX_MONEY);
        reject(market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> NOW));
        assertEquals(100, store.balance(buyer).get()); assertEquals(MAX_MONEY, store.balance(seller).get());
        assertEquals(ListingStatus.OPEN, market.listing(listing).get().status());
    }
    @Test void feeAccountOverflowRollsBackBothPlayers() throws Exception {
        UUID listing = offer(100, 500); fund(buyer, 100);
        store.tx(c -> Store.change(c, FEE_ACCOUNT, MAX_MONEY, "test", "test")).get();
        reject(market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> NOW));
        assertEquals(100, store.balance(buyer).get()); assertEquals(0, store.balance(seller).get());
    }
    @Test void downstreamDatabaseFailureRollsBackLedgerOwnershipAndListing() throws Exception {
        UUID listing = offer(100, 500); fund(buyer, 100); long ledger = count("ledger");
        store.tx(c -> Store.update(c, "CREATE TRIGGER fail_receipt BEFORE INSERT ON market_trades BEGIN SELECT RAISE(ABORT,'fixture'); END")).get();
        reject(market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> NOW));
        assertEquals(100, store.balance(buyer).get()); assertEquals(0, store.balance(seller).get());
        assertEquals(ledger, count("ledger")); assertEquals(ListingStatus.OPEN, market.listing(listing).get().status());
        assertEquals(seller, market.item(market.listing(listing).get().item()).get().owner());
    }
    @Test void orphanLedgerReceiptCannotMakeAnUnpaidPurchase() throws Exception {
        UUID listing = offer(100, 0), op = UUID.randomUUID(); fund(buyer, 200);
        store.money(buyer, -100, "market:buy:" + op, "external collision").get();
        reject(market.buy(op, buyer, listing, 100, () -> true, () -> NOW)); assertEquals(ListingStatus.OPEN, market.listing(listing).get().status());
    }
    @Test void selfPurchaseWrongPriceAndExactExpiryAreRejected() throws Exception {
        UUID listing = offer(100, 0); fund(buyer, 100); fund(seller, 100);
        reject(market.buy(UUID.randomUUID(), seller, listing, 100, () -> true, () -> NOW));
        reject(market.buy(UUID.randomUUID(), buyer, listing, 99, () -> true, () -> NOW));
        reject(market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> END));
        assertEquals(0, count("market_trades"));
    }
    @Test void maximumPriceAndFullFeeDoNotOverflow() throws Exception {
        UUID listing = offer(MAX_MONEY, 10_000); fund(buyer, MAX_MONEY);
        assertEquals(MAX_MONEY, market.buy(UUID.randomUUID(), buyer, listing, MAX_MONEY, () -> true, () -> NOW).get().fee());
        assertEquals(0, store.balance(seller).get()); assertEquals(0, store.balance(buyer).get());
    }
    @Test void listingReplayIsBoundToAllTermsAndSurvivesExpiry() throws Exception {
        UUID item = deposit(seller), listing = UUID.randomUUID();
        var first = market.list(listing, seller, item, 100, 500, END, () -> true, () -> NOW).get();
        assertEquals(first, market.list(listing, seller, item, 100, 500, END, () -> true, () -> END + 1).get());
        reject(market.list(listing, seller, item, 101, 500, END, () -> true, () -> NOW));
        reject(market.list(listing, seller, item, 100, 501, END, () -> true, () -> NOW));
        reject(market.list(listing, seller, item, 100, 500, END + 1, () -> true, () -> NOW));
        reject(market.list(listing, buyer, item, 100, 500, END, () -> true, () -> NOW));
        reject(market.list(UUID.randomUUID(), seller, item, 100, 500, END, () -> true, () -> NOW));
    }
    @Test void cancelledItemCanBeRelistedButOldListingCannotBePurchased() throws Exception {
        UUID listing = offer(100, 0); UUID item = market.listing(listing).get().item();
        reject(market.cancel(listing, buyer, () -> true, () -> NOW));
        assertEquals(ListingStatus.CANCELLED, market.cancel(listing, seller, () -> true, () -> NOW).get().status());
        market.cancel(listing, seller, () -> true, () -> NOW).get(); assertEquals(1, market.mailbox(seller, 10).get().size());
        market.list(UUID.randomUUID(), seller, item, 100, 0, END, () -> true, () -> NOW).get(); fund(buyer, 100);
        reject(market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> NOW));
    }
    @Test void soldListingCannotBeCancelledByFormerOwner() throws Exception {
        UUID listing = offer(100, 0); fund(buyer, 100); market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> NOW).get();
        reject(market.cancel(listing, seller, () -> true, () -> NOW));
        assertEquals(1, market.mailbox(buyer, 10).get().size());
    }
    @Test void expiryIsBoundedIdempotentAndReturnsEscrow() throws Exception {
        for (int i = 0; i < 3; i++) offer(100, 0);
        assertEquals(0, market.expire(END - 1, 2).get());
        assertEquals(2, market.expire(END, 2).get()); assertEquals(1, market.expire(END, 2).get());
        assertEquals(0, market.expire(END, 2).get()); assertEquals(3, market.mailbox(seller, 10).get().size());
    }
    @Test void cancelAtExpiryUsesExpiredState() throws Exception {
        UUID listing = offer(100, 0); assertEquals(ListingStatus.EXPIRED, market.cancel(listing, seller, () -> true, () -> END).get().status());
    }
    @Test void boundsRejectInvalidPricesFeesAndPageSizes() throws Exception {
        UUID item = deposit(seller);
        for (long price : new long[] {0, -1, MAX_MONEY + 1}) reject(market.list(UUID.randomUUID(), seller, item, price, 0, END, () -> true, () -> NOW));
        for (int fee : new int[] {-1, 10_001}) reject(market.list(UUID.randomUUID(), seller, item, 100, fee, END, () -> true, () -> NOW));
        reject(market.list(UUID.randomUUID(), seller, item, 100, 0, NOW, () -> true, () -> NOW));
        reject(market.mailbox(seller, 0)); reject(market.mailbox(seller, 101)); reject(market.expire(NOW, 101));
    }
    @Test void pendingBuyerCannotStartPurchase() throws Exception {
        UUID listing = offer(100, 0); fund(buyer, 100);
        market.prepareDeposit(UUID.randomUUID(), buyer, STACK, "a", "b", () -> true, () -> NOW).get();
        reject(market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> NOW)); assertEquals(100, store.balance(buyer).get());
    }
    @Test void tradeReceiptsAreImmutable() throws Exception {
        UUID listing = offer(100, 0); fund(buyer, 100); market.buy(UUID.randomUUID(), buyer, listing, 100, () -> true, () -> NOW).get();
        reject(store.tx(c -> Store.update(c, "UPDATE market_trades SET price=1")));
        reject(store.tx(c -> Store.update(c, "DELETE FROM market_trades"))); assertEquals(1, count("market_trades"));
    }
    @Test void pendingIntentCanBeDiscoveredByOwnerAfterRestart() throws Exception {
        assertNull(market.pendingIntent(seller).get());
        UUID id = UUID.randomUUID(); market.prepareDeposit(id, seller, STACK, "a", "b", () -> true, () -> NOW).get();
        market.reconcile(id, seller, "unknown", NOW).get();
        store.close(); store = new Store(dir.resolve("market.db")); store.start().get();
        market = new MarketRepository(store); market.start().get();
        assertEquals(id, market.pendingIntent(seller).get().id());
        assertEquals(IntentStatus.RECOVERY, market.pendingIntent(seller).get().status());
        assertNull(market.pendingIntent(buyer).get());
        market.reconcile(id, seller, "a", NOW).get(); assertNull(market.pendingIntent(seller).get());
    }
    @Test void catalogueIsBoundedAndExcludesExpiredAndSoldListings() throws Exception {
        UUID sold = offer(100, 0); offer(100, 0); offer(100, 0);
        assertEquals(2, market.openListings(NOW, 2).get().size());
        fund(buyer, 100); market.buy(UUID.randomUUID(), buyer, sold, 100, () -> true, () -> NOW).get();
        assertEquals(2, market.openListings(NOW, 10).get().size());
        assertTrue(market.openListings(END, 10).get().isEmpty());
        reject(market.openListings(NOW, 101)); reject(market.openListings(NOW, 0));
    }

    @Test void schemaBootstrapIsRepeatableAndRejectsFutureVersion() throws Exception {
        deposit(seller); market.start().get(); assertEquals(1, count("market_items"));
        store.tx(c -> Store.update(c, "UPDATE market_schema SET version=2 WHERE id=1")).get();
        reject(market.start()); assertEquals(1, count("market_items"));
    }
}
