package ru.warland.rtp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real temporary SQLite; never a game/server profile or production data directory. */
class RtpStateTest {
    @TempDir Path dir;
    Store db;
    final UUID player = UUID.randomUUID();
    static final long NOW = 1_800_000_000_000L;
    @BeforeEach void open() throws Exception { db = new Store(dir.resolve("rtp-test.db")); await(db.start()); }
    @AfterEach void close() { if (db != null) db.close(); }
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    static Throwable root(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error; }
    long scalar(String sql, Object... args) throws Exception { return await(db.submit(c -> Store.scalar(c, sql, args))); }
    long cooldown(UUID id) throws Exception { return await(RtpService.readCooldown(db, id, () -> true)); }
    RtpService.Reservation reserve(UUID id, long now) throws Exception {
        return await(RtpService.reserveCooldown(db, id, () -> true, 100, 0, () -> now));
    }

    @Test void reservationIsAtomicAndCreatesNoEconomicOrProfileWrites() throws Exception {
        assertEquals(0, cooldown(player));
        var first = reserve(player, NOW); var duplicate = reserve(player, NOW + 1);
        assertTrue(first.accepted()); assertEquals(NOW + 180_000, first.until());
        assertFalse(duplicate.accepted()); assertEquals(first.until(), duplicate.until());
        assertEquals(1, scalar("SELECT COUNT(*) FROM state WHERE namespace=?", RtpService.COOLDOWN_NAMESPACE));
        assertEquals(0, scalar("SELECT COUNT(*) FROM profiles")); assertEquals(0, scalar("SELECT COUNT(*) FROM accounts"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger"));
    }
    @Test void cooldownSurvivesStoreReopenAndIsPerUuid() throws Exception {
        var reservation = reserve(player, NOW);
        db.close(); db = null; open();
        assertEquals(reservation.until(), cooldown(player)); assertFalse(reserve(player, NOW + 1000).accepted());
        UUID other = UUID.randomUUID(); assertEquals(0, cooldown(other)); assertTrue(reserve(other, NOW).accepted());
        assertTrue(reserve(player, NOW + 180_000).accepted());
    }
    @Test void corruptedStateIsNotOverwrittenOrTreatedAsNoCooldown() throws Exception {
        await(db.state(RtpService.COOLDOWN_NAMESPACE, player.toString(), "{broken"));
        assertInstanceOf(IllegalArgumentException.class, root(assertThrows(Exception.class, () -> cooldown(player))));
        assertThrows(Exception.class, () -> reserve(player, NOW));
        assertEquals("{broken", await(db.state(RtpService.COOLDOWN_NAMESPACE, player.toString())));
    }
    @Test void missingOrExpiredSessionCannotReadOrReserve() throws Exception {
        assertInstanceOf(CancellationException.class, root(assertThrows(Exception.class,
                () -> await(RtpService.readCooldown(db, player, () -> false)))));
        assertInstanceOf(CancellationException.class, root(assertThrows(Exception.class,
                () -> await(RtpService.reserveCooldown(db, player, () -> false, 100, 0, () -> NOW)))));
        assertEquals(0, cooldown(player));
    }
    @Test void queuedOldSessionCannotBorrowAReconnectedPlayersAuthorization() throws Exception {
        AtomicBoolean oldSession = new AtomicBoolean(true), replacementSession = new AtomicBoolean(false);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var blocker = db.submit(c -> { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); return null; });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var pending = RtpService.reserveCooldown(db, player, oldSession::get, 100, 0, () -> NOW);
        oldSession.set(false); replacementSession.set(true); release.countDown(); await(blocker);
        assertInstanceOf(CancellationException.class, root(assertThrows(Exception.class, () -> await(pending))));
        assertEquals(0, cooldown(player)); assertTrue(replacementSession.get());
    }
    @Test void lateCancellationRetainsAnAlreadyAdmittedCooldownConservatively() throws Exception {
        AtomicBoolean session = new AtomicBoolean(true);
        var accepted = await(RtpService.reserveCooldown(db, player, session::get, 100, 0, () -> { session.set(false); return NOW; }));
        assertTrue(accepted.accepted()); assertFalse(session.get());
        assertEquals(accepted.until(), cooldown(player)); assertFalse(reserve(player, NOW + 50).accepted());
    }
    @Test void queuedDuplicateCannotBothReserve() throws Exception {
        var first = RtpService.reserveCooldown(db, player, () -> true, 100, 0, () -> NOW);
        var second = RtpService.reserveCooldown(db, player, () -> true, 100, 0, () -> NOW);
        assertTrue(await(first).accepted()); assertFalse(await(second).accepted());
    }
    @Test void targetAndNeighborClaimsAreCheckedInDbNotOnlyInCache() throws Exception {
        await(db.tx(c -> Store.update(c, "INSERT INTO nations(id,name,owner,cx,cy,cz,dimension,created) VALUES('test-nation','Test','test-owner',1600,70,0,?,1)", RtpPolicy.WORLD)));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int cx = 100 + dx, cz = dz;
            await(db.tx(c -> Store.update(c, "INSERT INTO claims(dimension,x,z,nation,created) VALUES(?,?,?,'test-nation',1)", RtpPolicy.WORLD, cx, cz)));
            assertThrows(Exception.class, () -> reserve(player, NOW)); assertEquals(0, cooldown(player));
            await(db.tx(c -> Store.update(c, "DELETE FROM claims WHERE dimension=? AND x=? AND z=?", RtpPolicy.WORLD, cx, cz)));
        }
        assertTrue(reserve(player, NOW).accepted());
    }
    @Test void distantClaimsDoNotBlockAndExistingProfilesArePreserved() throws Exception {
        await(db.tx(c -> {
            Store.update(c, "INSERT INTO profiles(uuid,name,joined,last_seen,tutorial,active_seconds) VALUES(?,'Existing',11,12,1,99)", player.toString());
            Store.update(c, "INSERT INTO nations(id,name,owner,cx,cy,cz,dimension,created) VALUES('distant','Distant','owner',0,70,0,?,1)", RtpPolicy.WORLD);
            Store.update(c, "INSERT INTO claims(dimension,x,z,nation,created) VALUES(?,0,0,'distant',1)", RtpPolicy.WORLD);
            return Store.update(c, "INSERT INTO state(namespace,key,json) VALUES('unrelated','keep','unchanged')");
        }));
        assertTrue(reserve(player, NOW).accepted());
        assertEquals(99, scalar("SELECT active_seconds FROM profiles WHERE uuid=?", player.toString()));
        assertEquals(1, scalar("SELECT tutorial FROM profiles WHERE uuid=?", player.toString()));
        assertEquals("unchanged", await(db.state("unrelated", "keep")));
        assertEquals(1, scalar("SELECT COUNT(*) FROM claims"));
    }
    @Test void clockRollbackAndOverflowNeverGrantAnEarlyRetry() throws Exception {
        var first = reserve(player, NOW);
        assertFalse(reserve(player, NOW - 1000).accepted()); assertEquals(first.until(), cooldown(player));
        UUID other = UUID.randomUUID(); assertThrows(Exception.class, () -> reserve(other, Long.MAX_VALUE));
        assertEquals(0, cooldown(other));
    }
}
