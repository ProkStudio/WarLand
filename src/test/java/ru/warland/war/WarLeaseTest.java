package ru.warland.war;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.warland.core.GameConfig;
import ru.warland.data.Store;
import ru.warland.nations.NationsService;
import ru.warland.war.WarRules.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated WAL DB and a held worker queue; no live game server, world or account. */
@Timeout(30)
class WarLeaseTest {
    enum Action { DECLARE, PEACE, SURRENDER, CAPTURE }
    private static final String WORLD = "minecraft:overworld";
    private static final long DAY = 86_400_000L;
    @TempDir Path dir;
    private Store db;
    private GameConfig config;
    private WarRepository wars;
    private String warId;
    private final UUID attacker = UUID.randomUUID(), defender = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID(), fourth = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(Instant.parse("2026-06-01T04:00:00Z").toEpochMilli());
    private final AtomicInteger generation = new AtomicInteger(1), checks = new AtomicInteger(), captures = new AtomicInteger();
    private final AtomicBoolean admitted = new AtomicBoolean(true);
    private final AtomicReference<Thread> captureThread = new AtomicReference<>(), checkThread = new AtomicReference<>();

    @BeforeEach void open() throws Exception {
        db = new Store(dir.resolve("war.db")); await(db.start());
        config = new GameConfig(); config.enableWarCapture = true;
        WarRepository seed = new WarRepository(db, config, clock::get);
        await(seed.initialize());
        nation("a", "Alpha", attacker, -3); nation("b", "Bravo", defender, 4);
        nation("c", "Charlie", outsider, 20); nation("d", "Delta", fourth, 30);
        for (int x = -2; x <= -1; x++) claim("a", x);
        for (int x = 0; x <= 3; x++) claim("b", x);
        await(seed.declare(attacker, "Bravo"));
        warId = text("SELECT id FROM wars");
        clock.set(number("SELECT starts FROM wars"));
        wars = new WarRepository(db, config, clock::get, this::lease);
    }
    @AfterEach void close() { if (db != null) db.close(); }

    private BooleanSupplier lease(UUID actor) {
        assertNotNull(actor);
        captures.incrementAndGet(); captureThread.set(Thread.currentThread());
        int capturedGeneration = generation.get();
        boolean capturedAdmission = admitted.get();
        return () -> {
            checks.incrementAndGet(); checkThread.set(Thread.currentThread());
            return capturedAdmission && admitted.get() && generation.get() == capturedGeneration;
        };
    }
    private static <T> T await(CompletableFuture<T> value) throws Exception { return value.get(8, TimeUnit.SECONDS); }
    private static Throwable root(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error; }
    private void denied(CompletableFuture<?> value) {
        assertInstanceOf(CancellationException.class, root(assertThrows(Exception.class, () -> await(value))));
    }
    private void update(String sql, Object... args) throws Exception { await(db.tx(c -> Store.update(c, sql, args))); }
    private long number(String sql, Object... args) throws Exception { return await(db.submit(c -> Store.scalar(c, sql, args))); }
    private String text(String sql, Object... args) throws Exception { return await(db.submit(c -> Store.string(c, sql, args))); }
    private void nation(String id, String name, UUID owner, int x) throws Exception {
        update("INSERT INTO nations(id,name,owner,cx,cy,cz,dimension,created) VALUES(?,?,?,?,64,0,?,?)",
                id, name, owner.toString(), x * 16, WORLD, clock.get() - 2 * DAY);
        update("INSERT INTO members(uuid,nation,rank,joined) VALUES(?,?,'LEADER',?)", owner.toString(), id, clock.get() - 2 * DAY);
        await(db.tx(c -> Store.change(c, Store.nation(id), 50_000, "fixture:" + id, "fixture")));
        claim(id, x);
    }
    private void claim(String nation, int x) throws Exception {
        update("INSERT INTO claims(dimension,x,z,nation,created) VALUES(?,?,0,?,?)", WORLD, x, nation, clock.get());
    }
    private CaptureKey key() { return new CaptureKey(warId, "a", WORLD, 0, 0, new Schedule(19, 2).windowKey(clock.get())); }
    private CompletableFuture<?> mutate(Action action, WarRepository repository) {
        return mutate(action, repository, action == Action.DECLARE ? outsider : attacker);
    }
    private CompletableFuture<?> mutate(Action action, WarRepository repository, UUID actor) {
        return switch (action) {
            case DECLARE -> repository.declare(actor, "Delta");
            case PEACE -> repository.peace(actor, "Bravo");
            case SURRENDER -> repository.surrender(actor, "Bravo");
            case CAPTURE -> repository.capture(key(), actor);
        };
    }
    private UUID delegate(Action action) throws Exception {
        UUID actor = UUID.randomUUID(); String nation = action == Action.DECLARE ? "c" : "a";
        update("INSERT INTO members(uuid,nation,rank,joined) VALUES(?,?,'GENERAL',?)", actor.toString(), nation, clock.get());
        update("INSERT INTO state(namespace,key,json) VALUES('ranks',?,?)", nation, "{\"GENERAL\":[\"war\"]}");
        assertEquals(nation, await(db.submit(c -> NationsService.require(c, actor, "war"))));
        return actor;
    }
    private List<String> snapshot() throws Exception {
        return await(db.submit(c -> {
            List<String> result = new ArrayList<>();
            for (String table : List.of("accounts", "ledger", "nations", "members", "claims", "wars",
                    "war_settings", "war_captures", "war_peace_offers", "audit")) {
                result.add(table);
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM " + table + " ORDER BY rowid")) {
                    while (r.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getString(i));
                        result.add(row.toString());
                    }
                }
            }
            return result;
        }));
    }
    private record Held(CountDownLatch release) implements AutoCloseable {
        @Override public void close() { release.countDown(); }
    }
    private Held holdWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        db.submit(c -> {
            entered.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test worker hold timed out");
            return null;
        });
        if (!entered.await(8, TimeUnit.SECONDS)) { release.countDown(); fail("Worker did not enter test hold"); }
        return new Held(release);
    }

    @ParameterizedTest @EnumSource(Action.class)
    void revocationWhileQueuedRejectsEveryMutationWithoutAnyDurableEffect(Action action) throws Exception {
        var before = snapshot(); CompletableFuture<?> queued;
        Thread caller = Thread.currentThread();
        try (Held ignored = holdWorker()) {
            queued = mutate(action, wars);
            assertEquals(1, captures.get()); assertEquals(0, checks.get());
            admitted.set(false);
        }
        denied(queued);
        assertEquals(1, captures.get()); assertEquals(1, checks.get());
        assertSame(caller, captureThread.get()); assertNotSame(caller, checkThread.get());
        assertEquals(before, snapshot());
    }

    @ParameterizedTest @EnumSource(Action.class)
    void sameUuidReconnectCannotReviveOldQueueButNewSessionCanAct(Action action) throws Exception {
        var before = snapshot(); CompletableFuture<?> queued;
        try (Held ignored = holdWorker()) { queued = mutate(action, wars); generation.incrementAndGet(); }
        denied(queued); assertEquals(before, snapshot());
        await(mutate(action, wars));
        assertNotEquals(before, snapshot()); assertEquals(2, captures.get()); assertEquals(2, checks.get());
    }

    @ParameterizedTest @EnumSource(Action.class)
    void pendingAdmissionCannotBecomeAnAuthorizedOldRequest(Action action) throws Exception {
        var before = snapshot(); CompletableFuture<?> queued;
        admitted.set(false);
        try (Held ignored = holdWorker()) { queued = mutate(action, wars); admitted.set(true); }
        denied(queued); assertEquals(before, snapshot());
    }

    @ParameterizedTest @EnumSource(Action.class)
    void validLeaseStillChecksCurrentDurableMembershipAndPermission(Action action) throws Exception {
        // A nation owner retains authority regardless of rank; test an actual delegated war permission.
        UUID actor = delegate(action);
        CompletableFuture<?> queued, rightsChange;
        // Queue the rights change ahead of the action, proving checks use transaction state, not stale cache.
        try (Held ignored = holdWorker()) {
            rightsChange = db.tx(c -> action == Action.CAPTURE
                    ? Store.update(c, "DELETE FROM members WHERE uuid=?", actor.toString())
                    : Store.update(c, "UPDATE members SET rank='MEMBER' WHERE uuid=?", actor.toString()));
            queued = mutate(action, wars, actor);
        }
        await(rightsChange);
        assertInstanceOf(SQLException.class, root(assertThrows(Exception.class, () -> await(queued))));
        assertEquals(1, checks.get());
        assertEquals(1, number("SELECT COUNT(*) FROM wars"));
        assertEquals(0, number("SELECT COUNT(*) FROM war_peace_offers"));
        assertEquals("ACTIVE", text("SELECT status FROM wars WHERE id=?", warId));
        assertEquals("b", text("SELECT nation FROM claims WHERE x=0 AND z=0"));
        assertEquals(0, number("SELECT COUNT(*) FROM war_captures"));
        assertEquals(1, number("SELECT COUNT(*) FROM ledger WHERE reason='war-declare'"));
        assertEquals(1, number("SELECT COUNT(*) FROM audit"));
    }

    @ParameterizedTest @EnumSource(value = Action.class, names = {"DECLARE", "PEACE", "SURRENDER"})
    void currentCustomWarPermissionIsReadAgainOnTheWorker(Action action) throws Exception {
        UUID actor = delegate(action); String nation = action == Action.DECLARE ? "c" : "a";
        CompletableFuture<?> queued, rightsChange;
        try (Held ignored = holdWorker()) {
            rightsChange = db.tx(c -> Store.update(c, "UPDATE state SET json=? WHERE namespace='ranks' AND key=?", "{\"GENERAL\":[]}", nation));
            queued = mutate(action, wars, actor);
        }
        await(rightsChange);
        assertInstanceOf(SQLException.class, root(assertThrows(Exception.class, () -> await(queued))));
        assertEquals(1, checks.get()); assertEquals(1, number("SELECT COUNT(*) FROM wars"));
        assertEquals(0, number("SELECT COUNT(*) FROM war_peace_offers"));
        assertEquals("ACTIVE", text("SELECT status FROM wars WHERE id=?", warId));
        assertEquals(1, number("SELECT COUNT(*) FROM audit"));
    }

    @ParameterizedTest @EnumSource(Action.class)
    void aFactoryExceptionFailsClosedAsAFuture(Action action) throws Exception {
        var before = snapshot();
        WarRepository broken = new WarRepository(db, config, clock::get, actor -> { throw new IllegalStateException("No live capture"); });
        var future = assertDoesNotThrow(() -> { return mutate(action, broken); });
        assertInstanceOf(IllegalStateException.class, root(assertThrows(Exception.class, () -> await(future))));
        assertEquals(before, snapshot());
    }

    @Test void nullLeaseFailsClosed() throws Exception {
        var before = snapshot();
        var broken = new WarRepository(db, config, clock::get, actor -> null);
        assertInstanceOf(NullPointerException.class, root(assertThrows(Exception.class, () -> await(broken.declare(outsider, "Delta")))));
        assertEquals(before, snapshot());
    }

    @Test void expiredWarSettlementDoesNotRequireAnOnlinePlayer() throws Exception {
        admitted.set(false); clock.set(number("SELECT ends FROM wars WHERE id=?", warId));
        await(wars.settle(warId)); await(wars.settle(warId));
        assertEquals("DRAW", text("SELECT status FROM wars WHERE id=?", warId));
        assertEquals(1, number("SELECT COUNT(*) FROM audit WHERE action='war-settle'"));
        assertEquals(0, captures.get()); assertEquals(0, checks.get());
    }

    @Test void restartInitializationCanSettleWithNoAuthenticatedActors() throws Exception {
        admitted.set(false); clock.set(number("SELECT ends FROM wars WHERE id=?", warId));
        await(wars.initialize());
        assertEquals("DRAW", text("SELECT status FROM wars WHERE id=?", warId));
        assertEquals(0, captures.get()); assertEquals(0, checks.get());
    }

    private WarService service(Function<UUID, BooleanSupplier> factory) throws Exception {
        NationsService nations = new NationsService(db, config);
        await(nations.refresh());
        WarService service = new WarService(db, config, nations, factory, player -> false);
        await(service.refresh());
        return service;
    }

    @Test void cancellingObserverCannotSuppressCommittedWarCachePublication() throws Exception {
        WarService service = service(this::lease); CompletableFuture<String> observer;
        try (Held ignored = holdWorker()) {
            observer = service.declare(outsider, "Delta"); assertTrue(observer.cancel(false));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (service.wars().stream().noneMatch(w -> w.attacker().equals("c")) && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(observer.isCancelled());
        assertTrue(service.wars().stream().anyMatch(w -> w.attacker().equals("c")));
        assertEquals(1, number("SELECT COUNT(*) FROM wars WHERE attacker='c'"));
    }

    @Test void revocationAfterWorkerAdmissionDoesNotHideCommittedWar() throws Exception {
        WarService service = service(actor -> () -> { boolean allowed = admitted.get(); admitted.set(false); return allowed; });
        await(service.declare(outsider, "Delta"));
        assertFalse(admitted.get()); assertTrue(service.wars().stream().anyMatch(w -> w.attacker().equals("c")));
        assertEquals(1, number("SELECT COUNT(*) FROM wars WHERE attacker='c'"));
    }

    @Test void compatibilityAdapterWithoutExplicitAuthorizationDeniesPlayerWrites() throws Exception {
        NationsService nations = new NationsService(db, config);
        WarService legacy = new WarService(db, config, nations);
        var before = snapshot();
        denied(legacy.declare(outsider, "Delta")); denied(legacy.peace(attacker, "Bravo")); denied(legacy.surrender(attacker, "Bravo"));
        assertEquals(before, snapshot());
    }
}
