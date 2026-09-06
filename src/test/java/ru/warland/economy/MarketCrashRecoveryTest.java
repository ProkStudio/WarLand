package ru.warland.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.Function;
import ru.warland.data.Store;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.economy.MarketRepository.*;

/** Real abrupt JVM exits, synthetic saved inventory strings, NOT Minecraft player-NBT QA. */
@Timeout(35)
class MarketCrashRecoveryTest {
    @TempDir Path dir;
    static final UUID SELLER = new UUID(0, 1), BUYER = new UUID(0, 2);
    static final UUID ITEM = new UUID(0, 3), LISTING = new UUID(0, 4), OP = new UUID(0, 5);
    @Test void depositCrashBeforeInventorySaveAborts() throws Exception { inventoryCrash("deposit", false); }
    @Test void depositCrashAfterInventorySaveRecoversEscrow() throws Exception { inventoryCrash("deposit", true); }
    @Test void deliveryCrashBeforeInventorySaveReturnsMailbox() throws Exception { inventoryCrash("delivery", false); }
    @Test void deliveryCrashAfterInventorySaveDoesNotRedeliver() throws Exception { inventoryCrash("delivery", true); }
    @Test void saleCrashInsideTransactionRollsBack() throws Exception { saleCrash(false); }
    @Test void saleCrashAfterCommitReplaysReceipt() throws Exception { saleCrash(true); }

    private void inventoryCrash(String kind, boolean after) throws Exception {
        launch(kind, after);
        try (Store store = new Store(dir.resolve("crash.db"))) {
            store.start().get(5, TimeUnit.SECONDS); MarketRepository market = new MarketRepository(store); market.start().get();
            UUID intent = kind.equals("deposit") ? ITEM : OP;
            assertEquals(IntentStatus.PREPARED, market.intent(intent).get().status());
            assertTrue(market.inventoryBlocked(SELLER).get());
            String saved = Files.readString(dir.resolve("inventory"));
            assertEquals(after ? "after" : "before", saved);
            var result = market.reconcile(intent, SELLER, saved, 1001).get();
            assertEquals(after ? IntentStatus.COMMITTED : IntentStatus.ABORTED, result.status());
            market.reconcile(intent, SELLER, saved, 1002).get();
            if (kind.equals("deposit")) assertEquals(after ? 1 : 0, market.mailbox(SELLER, 10).get().size());
            else {
                assertEquals(after ? ItemStatus.DELIVERED : ItemStatus.HELD, market.item(ITEM).get().status());
                assertEquals(after ? 0 : 1, market.mailbox(SELLER, 10).get().size());
            }
            assertFalse(market.inventoryBlocked(SELLER).get()); checkDatabase(store);
        }
    }
    private void saleCrash(boolean after) throws Exception {
        launch("sale", after);
        try (Store store = new Store(dir.resolve("crash.db"))) {
            store.start().get(5, TimeUnit.SECONDS); MarketRepository market = new MarketRepository(store); market.start().get();
            assertEquals(after ? 0 : 100, store.balance(BUYER).get());
            assertEquals(after ? 95 : 0, store.balance(SELLER).get());
            assertEquals(after ? ListingStatus.SOLD : ListingStatus.OPEN, market.listing(LISTING).get().status());
            assertEquals(after ? BUYER : SELLER, market.item(ITEM).get().owner());
            store.tx(c -> Store.update(c, "DROP TRIGGER IF EXISTS crash_market_receipt")).get();
            market.buy(OP, BUYER, LISTING, 100, 1001).get(); market.buy(OP, BUYER, LISTING, 100, 1001).get();
            assertEquals(0, store.balance(BUYER).get()); assertEquals(95, store.balance(SELLER).get());
            assertEquals(1, store.submit(c -> Store.scalar(c, "SELECT COUNT(*) FROM market_trades")).get());
            assertEquals(5, store.submit(c -> Store.scalar(c, "SELECT balance FROM accounts WHERE owner=?", FEE_ACCOUNT)).get());
            checkDatabase(store);
        }
    }
    private void checkDatabase(Store store) throws Exception {
        assertEquals("ok", store.submit(c -> Store.string(c, "PRAGMA quick_check")).get());
        assertEquals(0, store.submit(c -> Store.scalar(c, "SELECT COUNT(*) FROM pragma_foreign_key_check")).get());
    }
    private void launch(String kind, boolean after) throws Exception {
        Set<String> cp = new LinkedHashSet<>();
        for (Class<?> type : List.of(CrashWriter.class, Store.class, MarketRepository.class, org.sqlite.JDBC.class, org.slf4j.LoggerFactory.class))
            cp.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path log = dir.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m",
                "-cp", String.join(File.pathSeparator, cp), CrashWriter.class.getName(), dir.toString(), kind, Boolean.toString(after))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Crash child timeout");
            assertEquals(after ? 72 : 71, child.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception e) { return e.toString(); }
            });
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
        assertTrue(Files.exists(dir.resolve("crash.db-wal")), "Expected WAL without clean shutdown");
    }
    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            Path dir = Path.of(args[0]); boolean after = Boolean.parseBoolean(args[2]);
            Store store = new Store(dir.resolve("crash.db")); store.start().get();
            MarketRepository market = new MarketRepository(store); market.start().get();
            market.prepareDeposit(ITEM, SELLER, "synthetic-stack", "before", "after", 1000).get();
            if (args[1].equals("deposit")) {
                save(dir.resolve("inventory"), after ? "after" : "before");
            } else {
                market.reconcile(ITEM, SELLER, "after", 1000).get();
                if (args[1].equals("delivery")) {
                    market.prepareDelivery(OP, SELLER, ITEM, "before", "after", 1000).get();
                    save(dir.resolve("inventory"), after ? "after" : "before");
                } else {
                    store.money(BUYER, 100, "seed", "test").get();
                    market.list(LISTING, SELLER, ITEM, 100, 500, 2000, 1000).get();
                    if (!after) store.tx(c -> {
                        Function.create(c, "crash_market", new Function() {
                            @Override protected void xFunc() { Runtime.getRuntime().halt(71); }
                        });
                        Store.update(c, "CREATE TRIGGER crash_market_receipt AFTER INSERT ON market_trades BEGIN SELECT crash_market(); END");
                        return null;
                    }).get();
                    market.buy(OP, BUYER, LISTING, 100, 1000).get();
                }
            }
            Runtime.getRuntime().halt(after ? 72 : 71);
        }
        private static void save(Path file, String value) throws Exception {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
        }
    }
}
