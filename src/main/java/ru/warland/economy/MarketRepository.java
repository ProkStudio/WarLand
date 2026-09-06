package ru.warland.economy;

import ru.warland.data.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * SQLite-only market domain. This is NOT an inventory adapter or a public gameplay feature.
 * All writes share Store's serial transaction worker. Callers must supply server-produced,
 * canonical snapshots read back from durable player data, never a client acknowledgement.
 * Actor commands require the captured session lease and a trusted server clock.
 * Admission and clock sampling happen on the Store worker, not when enqueued.
 * An ambiguous inventory outcome stays locked until reconciliation proves before or after.
 */
public final class MarketRepository {
    public static final long MAX_MONEY = 1_000_000_000_000L;
    public static final String FEE_ACCOUNT = "system:market-fees";
    private static final int MAX_SNAPSHOT = 262_144;
    private final Store store;

    public enum IntentKind { DEPOSIT, DELIVERY }
    public enum IntentStatus { PREPARED, RECOVERY, COMMITTED, ABORTED }
    public enum ItemStatus { HELD, LISTED, DELIVERING, DELIVERED }
    public enum ListingStatus { OPEN, SOLD, CANCELLED, EXPIRED }
    public record Intent(UUID id, UUID player, IntentKind kind, UUID item, String stack,
                         String beforeSnapshot, String afterSnapshot, IntentStatus status) {}
    public record Item(UUID id, UUID owner, String stack, ItemStatus status) {}
    public record Listing(UUID id, UUID item, UUID seller, long price, int feeBps,
                          long expires, ListingStatus status) {}
    public record Trade(UUID id, UUID listing, UUID buyer, long price, long fee) {}

    public MarketRepository(Store store) { this.store = Objects.requireNonNull(store); }

    /** Additive, opt-in schema; no migration of the old, unused market tables. */
    public CompletableFuture<Void> start() {
        return store.tx(c -> {
            Store.update(c, "CREATE TABLE IF NOT EXISTS market_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            Store.update(c, "INSERT OR IGNORE INTO market_schema(id,version) VALUES(1,1)");
            if (Store.scalar(c, "SELECT version FROM market_schema WHERE id=1") != 1)
                throw new SQLException("Unsupported market schema; migration and backup required");
            for (String sql : SCHEMA) Store.update(c, sql);
            return null;
        });
    }

    public CompletableFuture<Intent> prepareDeposit(UUID operation, UUID player, String stack,
                                                    String before, String after, BooleanSupplier lease, LongSupplier clock) {
        return actorTx(lease, clock, (c, now) -> prepare(c, operation, player, IntentKind.DEPOSIT, operation,
                text(stack, 65_536), before, after, now));
    }

    public CompletableFuture<Intent> prepareDelivery(UUID operation, UUID player, UUID item,
                                                     String before, String after, BooleanSupplier lease, LongSupplier clock) {
        return actorTx(lease, clock, (c, now) -> {
            Intent previous = findIntent(c, operation);
            if (previous != null) {
                sameIntent(previous, player, IntentKind.DELIVERY, item, previous.stack(), before, after);
                return previous;
            }
            Item owned = requireItem(c, item);
            require(owned.owner().equals(player) && owned.status() == ItemStatus.HELD, "Item not available to owner");
            Intent intent = prepare(c, operation, player, IntentKind.DELIVERY, item, owned.stack(), before, after, now);
            Store.update(c, "UPDATE market_items SET status='DELIVERING' WHERE id=?", item.toString());
            return intent;
        });
    }

    private Intent prepare(Connection c, UUID operation, UUID player, IntentKind kind, UUID item,
                           String stack, String before, String after, long now) throws SQLException {
        Objects.requireNonNull(operation); Objects.requireNonNull(player); Objects.requireNonNull(item);
        text(before, MAX_SNAPSHOT); text(after, MAX_SNAPSHOT); time(now);
        require(!before.equals(after), "Inventory snapshots must differ");
        Intent previous = findIntent(c, operation);
        if (previous != null) {
            sameIntent(previous, player, kind, item, stack, before, after);
            return previous;
        }
        require(!blocked(c, player), "Player has unresolved inventory intent");
        Store.update(c, "INSERT INTO market_intents(id,player,kind,item,stack,before_snapshot,after_snapshot,status,created) VALUES(?,?,?,?,?,?,?,'PREPARED',?)",
                operation.toString(), player.toString(), kind.name(), item.toString(), stack, before, after, now);
        audit(c, player, "market.intent.prepare", operation, now);
        return findIntent(c, operation);
    }

    /** System recovery, intentionally independent of an online session: saved player-data evidence
     * decides the outcome even after disconnect. Never modifies Minecraft or trusts client ACKs. */
    public CompletableFuture<Intent> reconcile(UUID operation, UUID player, String persistedSnapshot, long now) {
        return store.tx(c -> {
            text(persistedSnapshot, MAX_SNAPSHOT); time(now);
            Intent intent = findIntent(c, operation);
            require(intent != null && intent.player().equals(player), "Intent not owned by player");
            if (intent.status() == IntentStatus.COMMITTED || intent.status() == IntentStatus.ABORTED) return intent;
            IntentStatus outcome = persistedSnapshot.equals(intent.afterSnapshot()) ? IntentStatus.COMMITTED
                    : persistedSnapshot.equals(intent.beforeSnapshot()) ? IntentStatus.ABORTED : IntentStatus.RECOVERY;
            if (outcome == intent.status()) return intent;
            if (intent.kind() == IntentKind.DEPOSIT && outcome == IntentStatus.COMMITTED) {
                Store.update(c, "INSERT INTO market_items(id,owner,stack,status) VALUES(?,?,?,'HELD')",
                        intent.item().toString(), player.toString(), intent.stack());
            } else if (intent.kind() == IntentKind.DELIVERY && outcome != IntentStatus.RECOVERY) {
                int changed = Store.update(c, "UPDATE market_items SET status=? WHERE id=? AND owner=? AND status='DELIVERING'",
                        outcome == IntentStatus.COMMITTED ? "DELIVERED" : "HELD", intent.item().toString(), player.toString());
                require(changed == 1, "Delivery reservation inconsistent");
            }
            Store.update(c, "UPDATE market_intents SET status=? WHERE id=?", outcome.name(), operation.toString());
            audit(c, player, "market.intent." + outcome.name().toLowerCase(java.util.Locale.ROOT), operation, now);
            return findIntent(c, operation);
        });
    }

    public CompletableFuture<Boolean> inventoryBlocked(UUID player) { return store.submit(c -> blocked(c, player)); }
    /** Resolve the single active intent on login, without scanning historical snapshots. */
    public CompletableFuture<Intent> pendingIntent(UUID player) {
        return store.submit(c -> {
            String id = Store.string(c, "SELECT id FROM market_intents WHERE player=? AND status IN ('PREPARED','RECOVERY')", player.toString());
            return id == null ? null : findIntent(c, UUID.fromString(id));
        });
    }

    public CompletableFuture<List<Listing>> openListings(long now, int limit) {
        return store.submit(c -> {
            time(now); page(limit);
            List<Listing> offers = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM market_listings WHERE status='OPEN' AND expires>? ORDER BY expires,id LIMIT ?")) {
                Store.bind(p, now, limit);
                try (ResultSet r = p.executeQuery()) { while (r.next()) offers.add(readListing(r)); }
            }
            return List.copyOf(offers);
        });
    }

    public CompletableFuture<Intent> intent(UUID operation) { return store.submit(c -> findIntent(c, operation)); }
    public CompletableFuture<Item> item(UUID item) { return store.submit(c -> requireItem(c, item)); }
    public CompletableFuture<Listing> listing(UUID listing) { return store.submit(c -> requireListing(c, listing)); }

    public CompletableFuture<Listing> list(UUID id, UUID seller, UUID item, long price, int feeBps, long expires, BooleanSupplier lease, LongSupplier clock) {
        return actorTx(lease, clock, (c, now) -> {
            Objects.requireNonNull(id); Objects.requireNonNull(seller); Objects.requireNonNull(item);
            money(price); require(feeBps >= 0 && feeBps <= 10_000, "Invalid fee"); time(now); time(expires);
            Listing previous = findListing(c, id);
            if (previous != null) {
                require(previous.seller().equals(seller) && previous.item().equals(item) && previous.price() == price
                        && previous.feeBps() == feeBps && previous.expires() == expires, "Listing idempotency conflict");
                return previous;
            }
            require(expires > now, "Listing already expired");
            require(!blocked(c, seller), "Seller has unresolved inventory intent");
            Item owned = requireItem(c, item);
            require(owned.owner().equals(seller) && owned.status() == ItemStatus.HELD, "Item not available to seller");
            Store.update(c, "INSERT INTO market_listings(id,item,seller,price,fee_bps,expires,status,created) VALUES(?,?,?,?,?,?,'OPEN',?)",
                    id.toString(), item.toString(), seller.toString(), price, feeBps, expires, now);
            Store.update(c, "UPDATE market_items SET status='LISTED' WHERE id=?", item.toString());
            audit(c, seller, "market.list", id, now);
            return requireListing(c, id);
        });
    }

    /** Debit, seller payout, fee ledger, ownership and receipt commit together or all roll back. */
    public CompletableFuture<Trade> buy(UUID operation, UUID buyer, UUID listing, long expectedPrice, BooleanSupplier lease, LongSupplier clock) {
        return actorTx(lease, clock, (c, now) -> {
            Objects.requireNonNull(operation); Objects.requireNonNull(buyer); Objects.requireNonNull(listing);
            money(expectedPrice); time(now);
            Trade previous = findTrade(c, operation);
            if (previous != null) {
                require(previous.buyer().equals(buyer) && previous.listing().equals(listing)
                        && previous.price() == expectedPrice, "Purchase idempotency conflict");
                return previous;
            }
            Listing offer = requireListing(c, listing);
            require(offer.status() == ListingStatus.OPEN && now < offer.expires(), "Listing not open");
            require(!offer.seller().equals(buyer), "Cannot buy own listing");
            require(offer.price() == expectedPrice, "Price changed");
            require(!blocked(c, buyer), "Buyer has unresolved inventory intent");
            long fee = offer.price() * offer.feeBps() / 10_000L;
            String ledgerOperation = "market:buy:" + operation;
            require(Store.scalar(c, "SELECT COUNT(*) FROM ledger WHERE operation=? AND owner IN (?,?,?)",
                    ledgerOperation, Store.player(buyer), Store.player(offer.seller()), FEE_ACCOUNT) == 0,
                    "Ledger operation exists without market receipt");
            require(Store.change(c, Store.player(buyer), -offer.price(), ledgerOperation, "market.purchase"), "Insufficient funds");
            require(Store.change(c, Store.player(offer.seller()), offer.price() - fee, ledgerOperation, "market.sale"), "Seller balance limit");
            if (fee != 0) require(Store.change(c, FEE_ACCOUNT, fee, ledgerOperation, "market.fee"), "Fee account balance limit");
            require(Store.update(c, "UPDATE market_items SET owner=?,status='HELD' WHERE id=? AND owner=? AND status='LISTED'",
                    buyer.toString(), offer.item().toString(), offer.seller().toString()) == 1, "Escrow inconsistent");
            Store.update(c, "UPDATE market_listings SET status='SOLD' WHERE id=?", listing.toString());
            Store.update(c, "INSERT INTO market_trades(id,listing,buyer,price,fee) VALUES(?,?,?,?,?)",
                    operation.toString(), listing.toString(), buyer.toString(), offer.price(), fee);
            audit(c, buyer, "market.buy", listing, now);
            return findTrade(c, operation);
        });
    }

    public CompletableFuture<Listing> cancel(UUID listing, UUID seller, BooleanSupplier lease, LongSupplier clock) {
        return actorTx(lease, clock, (c, now) -> {
            time(now);
            Listing offer = requireListing(c, listing);
            require(offer.seller().equals(seller), "Listing not owned by seller");
            if (offer.status() == ListingStatus.CANCELLED || offer.status() == ListingStatus.EXPIRED) return offer;
            require(offer.status() == ListingStatus.OPEN, "Listing already sold");
            release(c, offer, now >= offer.expires() ? ListingStatus.EXPIRED : ListingStatus.CANCELLED, now);
            return requireListing(c, listing);
        });
    }

    public CompletableFuture<Integer> expire(long now, int limit) {
        return store.tx(c -> {
            time(now); page(limit);
            List<Listing> expired = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM market_listings WHERE status='OPEN' AND expires<=? ORDER BY expires,id LIMIT ?")) {
                Store.bind(p, now, limit);
                try (ResultSet r = p.executeQuery()) { while (r.next()) expired.add(readListing(r)); }
            }
            for (Listing offer : expired) release(c, offer, ListingStatus.EXPIRED, now);
            return expired.size();
        });
    }

    public CompletableFuture<List<Item>> mailbox(UUID player, int limit) {
        return store.submit(c -> {
            page(limit);
            List<Item> items = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM market_items WHERE owner=? AND status='HELD' ORDER BY id LIMIT ?")) {
                Store.bind(p, player.toString(), limit);
                try (ResultSet r = p.executeQuery()) { while (r.next()) items.add(readItem(r)); }
            }
            return List.copyOf(items);
        });
    }

    private static void release(Connection c, Listing offer, ListingStatus status, long now) throws SQLException {
        require(Store.update(c, "UPDATE market_items SET status='HELD' WHERE id=? AND owner=? AND status='LISTED'",
                offer.item().toString(), offer.seller().toString()) == 1, "Escrow inconsistent");
        Store.update(c, "UPDATE market_listings SET status=? WHERE id=?", status.name(), offer.id().toString());
        audit(c, offer.seller(), "market." + status.name().toLowerCase(java.util.Locale.ROOT), offer.id(), now);
    }

    @FunctionalInterface
    private interface ActorWork<T> { T run(Connection connection, long now) throws Exception; }

    /** No unleased overload: a future gameplay adapter must supply its captured session lease.
     * Clock must be the server's wall clock (e.g. System::currentTimeMillis), never client time
     * or a pre-sampled request timestamp. Clock/lease must not access Minecraft off-thread.
     * Revocation before worker admission cancels even an idempotent replay. Once admitted,
     * the short atomic transaction completes; reconnect cannot resurrect the old lease.
     */
    private <T> CompletableFuture<T> actorTx(BooleanSupplier lease, LongSupplier clock, ActorWork<T> work) {
        Objects.requireNonNull(lease, "lease"); Objects.requireNonNull(clock, "clock");
        return store.tx(lease, c -> {
            long now = clock.getAsLong();
            time(now);
            return work.run(c, now);
        });
    }

    private static boolean blocked(Connection c, UUID player) throws SQLException {
        return Store.scalar(c, "SELECT COUNT(*) FROM market_intents WHERE player=? AND status IN ('PREPARED','RECOVERY')", player.toString()) != 0;
    }
    private static void sameIntent(Intent old, UUID player, IntentKind kind, UUID item, String stack, String before, String after) throws SQLException {
        require(old.player().equals(player) && old.kind() == kind && old.item().equals(item) && old.stack().equals(stack)
                && old.beforeSnapshot().equals(before) && old.afterSnapshot().equals(after), "Intent idempotency conflict");
    }
    private static Intent findIntent(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM market_intents WHERE id=?")) {
            Store.bind(p, id.toString());
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return new Intent(id, UUID.fromString(r.getString("player")), IntentKind.valueOf(r.getString("kind")),
                        UUID.fromString(r.getString("item")), r.getString("stack"), r.getString("before_snapshot"),
                        r.getString("after_snapshot"), IntentStatus.valueOf(r.getString("status")));
            }
        }
    }
    private static Item readItem(ResultSet r) throws SQLException {
        return new Item(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("owner")), r.getString("stack"), ItemStatus.valueOf(r.getString("status")));
    }
    private static Item requireItem(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM market_items WHERE id=?")) {
            Store.bind(p, id.toString());
            try (ResultSet r = p.executeQuery()) { require(r.next(), "Item not found"); return readItem(r); }
        }
    }
    private static Listing readListing(ResultSet r) throws SQLException {
        return new Listing(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("item")), UUID.fromString(r.getString("seller")),
                r.getLong("price"), r.getInt("fee_bps"), r.getLong("expires"), ListingStatus.valueOf(r.getString("status")));
    }
    private static Listing findListing(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM market_listings WHERE id=?")) {
            Store.bind(p, id.toString());
            try (ResultSet r = p.executeQuery()) { return r.next() ? readListing(r) : null; }
        }
    }
    private static Listing requireListing(Connection c, UUID id) throws SQLException {
        Listing result = findListing(c, id); require(result != null, "Listing not found"); return result;
    }
    private static Trade findTrade(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM market_trades WHERE id=?")) {
            Store.bind(p, id.toString());
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? new Trade(id, UUID.fromString(r.getString("listing")), UUID.fromString(r.getString("buyer")), r.getLong("price"), r.getLong("fee")) : null;
            }
        }
    }
    private static void audit(Connection c, UUID actor, String action, UUID target, long now) throws SQLException {
        Store.update(c, "INSERT INTO audit(actor,action,target,created) VALUES(?,?,?,?)", actor.toString(), action, target.toString(), now);
    }
    private static void require(boolean valid, String message) throws SQLException { if (!valid) throw new SQLException(message); }
    private static String text(String value, int limit) throws SQLException { require(value != null && !value.isBlank() && value.length() <= limit, "Invalid or oversized payload"); return value; }
    private static void money(long value) throws SQLException { require(value > 0 && value <= MAX_MONEY, "Invalid price"); }
    private static void time(long value) throws SQLException { require(value > 0, "Invalid timestamp"); }
    private static void page(int value) throws SQLException { require(value > 0 && value <= 100, "Page size must be 1..100"); }

    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS market_intents(id TEXT PRIMARY KEY,player TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('DEPOSIT','DELIVERY')),item TEXT NOT NULL,stack TEXT NOT NULL,before_snapshot TEXT NOT NULL,after_snapshot TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('PREPARED','RECOVERY','COMMITTED','ABORTED')),created INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS market_one_pending ON market_intents(player) WHERE status IN ('PREPARED','RECOVERY')",
        "CREATE TABLE IF NOT EXISTS market_items(id TEXT PRIMARY KEY REFERENCES market_intents(id),owner TEXT NOT NULL,stack TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('HELD','LISTED','DELIVERING','DELIVERED')))",
        "CREATE INDEX IF NOT EXISTS market_mailbox_owner ON market_items(owner,status,id)",
        "CREATE TABLE IF NOT EXISTS market_listings(id TEXT PRIMARY KEY,item TEXT NOT NULL REFERENCES market_items(id),seller TEXT NOT NULL,price INTEGER NOT NULL CHECK(price BETWEEN 1 AND 1000000000000),fee_bps INTEGER NOT NULL CHECK(fee_bps BETWEEN 0 AND 10000),expires INTEGER NOT NULL,status TEXT NOT NULL CHECK(status IN ('OPEN','SOLD','CANCELLED','EXPIRED')),created INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS market_one_listing ON market_listings(item) WHERE status='OPEN'",
        "CREATE INDEX IF NOT EXISTS market_listing_expiry ON market_listings(status,expires,id)",
        "CREATE TABLE IF NOT EXISTS market_trades(id TEXT PRIMARY KEY,listing TEXT NOT NULL UNIQUE REFERENCES market_listings(id),buyer TEXT NOT NULL,price INTEGER NOT NULL CHECK(price BETWEEN 1 AND 1000000000000),fee INTEGER NOT NULL CHECK(fee BETWEEN 0 AND price))",
        "CREATE TRIGGER IF NOT EXISTS market_trade_no_update BEFORE UPDATE ON market_trades BEGIN SELECT RAISE(ABORT,'immutable market trade'); END",
        "CREATE TRIGGER IF NOT EXISTS market_trade_no_delete BEFORE DELETE ON market_trades BEGIN SELECT RAISE(ABORT,'immutable market trade'); END"
    };
}
