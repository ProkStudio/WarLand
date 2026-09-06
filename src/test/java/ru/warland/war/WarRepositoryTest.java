package ru.warland.war;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.core.GameConfig;
import ru.warland.data.Store;
import ru.warland.war.WarRules.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real temporary WAL database; never opens a Minecraft world or production data. */
@Timeout(20)
class WarRepositoryTest {
    private static final String WORLD = "minecraft:overworld";
    private static final long DAY = 86_400_000L;
    @TempDir Path dir;
    private Store db;
    private GameConfig config;
    private WarRepository wars;
    private final UUID attacker = UUID.randomUUID(), defender = UUID.randomUUID(), outsider = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(Instant.parse("2026-06-01T04:00:00Z").toEpochMilli());

    @BeforeEach void open() throws Exception {
        db = new Store(dir.resolve("war.db")); await(db.start());
        config = new GameConfig(); config.enableWarCapture = true;
        wars = new WarRepository(db, config, clock::get);
        await(wars.initialize());
        nation("a", "Alpha", attacker, -3); nation("b", "Bravo", defender, 4); nation("c", "Charlie", outsider, 20);
        for (int x = -2; x <= -1; x++) claim("a", x, 0);
        for (int x = 0; x <= 3; x++) claim("b", x, 0);
    }
    @AfterEach void close() { if (db != null) db.close(); }
    private static <T> T await(CompletableFuture<T> value) throws Exception { return value.get(10, TimeUnit.SECONDS); }
    private void update(String sql, Object... values) throws Exception { await(db.tx(c -> Store.update(c, sql, values))); }
    private long count(String sql, Object... values) throws Exception { return await(db.submit(c -> Store.scalar(c, sql, values))); }
    private String text(String sql, Object... values) throws Exception { return await(db.submit(c -> Store.string(c, sql, values))); }
    private void rejects(CompletableFuture<?> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(future));
        assertInstanceOf(SQLException.class, e.getCause());
    }
    private void nation(String id, String name, UUID owner, int capital) throws Exception {
        update("INSERT INTO nations(id,name,owner,cx,cy,cz,dimension,created) VALUES(?,?,?,?,64,0,?,?)", id, name, owner.toString(), capital * 16, WORLD, clock.get() - 2 * DAY);
        update("INSERT INTO members(uuid,nation,rank,joined) VALUES(?,?,'LEADER',?)", owner.toString(), id, clock.get() - 2 * DAY);
        await(db.tx(c -> Store.change(c, Store.nation(id), 50_000, "fixture-" + id, "fixture")));
        claim(id, capital, 0);
    }
    private void claim(String nation, int x, int z) throws Exception { update("INSERT INTO claims(dimension,x,z,nation,created) VALUES(?,?,?,?,?)", WORLD, x, z, nation, clock.get()); }
    private String declare() throws Exception { await(wars.declare(attacker, "Bravo")); return text("SELECT id FROM wars"); }
    private String active() throws Exception {
        String id = declare(); clock.set(count("SELECT starts FROM wars WHERE id=?", id)); return id;
    }
    private CaptureKey key(String id, String side, int x, int z) {
        return new CaptureKey(id, side, WORLD, x, z, new Schedule(config.warHourMoscow, config.warWindowHours).windowKey(clock.get()));
    }
    private long treasury() throws Exception { return count("SELECT balance FROM accounts WHERE owner='nation:a'"); }
    private void noCapture() throws Exception {
        assertEquals("b", text("SELECT nation FROM claims WHERE dimension=? AND x=0 AND z=0", WORLD));
        assertEquals(0, count("SELECT COUNT(*) FROM war_captures"));
        assertEquals(0, count("SELECT SUM(attack_score+defend_score) FROM wars"));
    }

    @Test void declarationChargesOnceFreezesScheduleAndKeepsExactlyTwelveHours() throws Exception {
        long declared = clock.get(); String id = declare();
        assertEquals(45_000, treasury());
        assertEquals(declared + WarRules.MOBILIZATION_MILLIS, count("SELECT starts FROM wars"));
        assertEquals(declared + WarRules.MOBILIZATION_MILLIS + 72 * 3_600_000L, count("SELECT ends FROM wars"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit WHERE action='war-declare'"));
        config.warHourMoscow = 3; config.warWindowHours = 1; config.warImmunityDays = 5;
        await(wars.initialize());
        assertEquals(new Schedule(19, 2), await(wars.snapshot()).schedules().get(id));
        assertEquals(7, count("SELECT immunity_days FROM war_settings WHERE war=?", id));
    }
    @Test void concurrentDuplicateDeclarationsAndReverseDeclarationChargeOnlyOneSide() throws Exception {
        List<CompletableFuture<String>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) attempts.add(i % 2 == 0 ? wars.declare(attacker, "Bravo") : wars.declare(defender, "Alpha"));
        long success = 0;
        for (var attempt : attempts) { try { await(attempt); success++; } catch (ExecutionException expected) { assertInstanceOf(SQLException.class, expected.getCause()); } }
        assertEquals(1, success);
        assertEquals(95_000, count("SELECT SUM(balance) FROM accounts WHERE owner IN ('nation:a','nation:b')"));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger WHERE reason='war-declare'"));
    }
    @Test void disabledWarCannotChargeAndPermissionTargetAndAgeAreRevalidated() throws Exception {
        config.enableWarCapture = false; rejects(wars.declare(attacker, "Bravo")); config.enableWarCapture = true;
        rejects(wars.declare(attacker, "Alpha")); rejects(wars.declare(attacker, "Missing")); rejects(wars.declare(UUID.randomUUID(), "Bravo"));
        UUID member = UUID.randomUUID(); update("INSERT INTO members(uuid,nation,rank,joined) VALUES(?,'a','MEMBER',?)", member.toString(), clock.get());
        rejects(wars.declare(member, "Bravo"));
        update("UPDATE nations SET created=? WHERE id='b'", clock.get() - DAY + 1);
        rejects(wars.declare(attacker, "Bravo"));
        assertEquals(50_000, treasury()); assertEquals(0, count("SELECT COUNT(*) FROM wars"));
        clock.incrementAndGet(); await(wars.declare(attacker, "Bravo"));
    }
    @Test void invalidConfigCannotShortenMobilizationOrCreateMoney() throws Exception {
        config.mobilizationHours = 1; rejects(wars.declare(attacker, "Bravo")); config.mobilizationHours = 12;
        config.warCost = -1; rejects(wars.declare(attacker, "Bravo")); config.warCost = 5000;
        config.warImmunityDays = Long.MAX_VALUE; rejects(wars.declare(attacker, "Bravo"));
        assertEquals(50_000, treasury()); assertEquals(0, count("SELECT COUNT(*) FROM wars"));
    }
    @Test void pactBlocksDeclarationButExpiredOfferDoesNot() throws Exception {
        update("INSERT INTO diplomacy(a,b,state,offered_by,expires) VALUES('b','a','PACT','b',?)", clock.get() + 1000);
        rejects(wars.declare(attacker, "Bravo"));
        clock.addAndGet(1000); await(wars.declare(attacker, "Bravo"));
    }
    @Test void insufficientFundsAndSqlFailureRollbackDeclaration() throws Exception {
        config.warCost = 50_001; rejects(wars.declare(attacker, "Bravo")); config.warCost = 5000;
        update("CREATE TEMP TRIGGER fail_settings BEFORE INSERT ON war_settings BEGIN SELECT RAISE(ABORT,'simulated'); END");
        rejects(wars.declare(attacker, "Bravo"));
        assertEquals(50_000, treasury()); assertEquals(0, count("SELECT COUNT(*) FROM wars"));
        assertEquals(0, count("SELECT COUNT(*) FROM ledger WHERE reason='war-declare'"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit WHERE action='war-declare'"));
    }
    @Test void captureCannotSkipMobilizationOrCrossClosedWindow() throws Exception {
        String id = declare(); rejects(wars.capture(key(id, "a", 0, 0), attacker));
        long starts = count("SELECT starts FROM wars"); clock.set(starts - 1);
        rejects(wars.capture(key(id, "a", 0, 0), attacker)); clock.set(starts + 7_200_000L);
        rejects(wars.capture(key(id, "a", 0, 0), attacker)); noCapture();
    }
    @Test void captureTransfersClaimAndScoreTogetherAndDuplicatesCannotScoreAgain() throws Exception {
        String id = active(); CaptureKey key = key(id, "a", 0, 0);
        List<CompletableFuture<Void>> attempts = new ArrayList<>();
        for (int i = 0; i < 10; i++) attempts.add(wars.capture(key, attacker));
        int successes = 0; for (var attempt : attempts) { try { await(attempt); successes++; } catch (ExecutionException expected) { assertInstanceOf(SQLException.class, expected.getCause()); } }
        assertEquals(1, successes); assertEquals("a", text("SELECT nation FROM claims WHERE x=0 AND z=0"));
        assertEquals(20, count("SELECT attack_score FROM wars")); assertEquals(1, count("SELECT COUNT(*) FROM war_captures"));
        assertEquals(clock.get(), count("SELECT last_capture FROM wars"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit WHERE action='war-capture'"));
        rejects(wars.capture(key(id, "b", 0, 0), defender));
        assertEquals(0, count("SELECT defend_score FROM wars"));
    }
    @Test void captureFailsClosedOnDisabledFlagStaleStatusDiplomacyAndMembership() throws Exception {
        String id = active(); CaptureKey key = key(id, "a", 0, 0);
        config.enableWarCapture = false; rejects(wars.capture(key, attacker)); config.enableWarCapture = true;
        update("UPDATE wars SET status='PEACE'"); rejects(wars.capture(key, attacker)); update("UPDATE wars SET status='ACTIVE'");
        update("INSERT INTO diplomacy(a,b,state,offered_by,expires) VALUES('a','b','PACT','b',?)", clock.get() + 1000);
        rejects(wars.capture(key, attacker)); update("DELETE FROM diplomacy");
        rejects(wars.capture(key(id, "c", 0, 0), outsider)); rejects(wars.capture(key, defender));
        update("DELETE FROM members WHERE uuid=?", attacker.toString()); rejects(wars.capture(key, attacker)); noCapture();
    }
    @Test void captureRechecksCapitalOwnershipAdjacencyAndDimensionFromDatabase() throws Exception {
        String id = active(); CaptureKey key = key(id, "a", 0, 0);
        rejects(wars.capture(key(id, "a", 4, 0), attacker));
        rejects(wars.capture(new CaptureKey(id, "a", "minecraft:the_nether", 0, 0, key.window()), attacker));
        update("UPDATE nations SET cx=0 WHERE id='b'"); rejects(wars.capture(key, attacker)); update("UPDATE nations SET cx=64 WHERE id='b'");
        update("DELETE FROM claims WHERE x=-1 AND z=0"); rejects(wars.capture(key, attacker)); claim("a", -1, 0);
        update("UPDATE claims SET nation='c' WHERE x=0 AND z=0"); rejects(wars.capture(key, attacker));
        update("UPDATE claims SET nation='b' WHERE x=0 AND z=0"); noCapture();
    }
    @Test void captureCannotLeaveDefenderIslandsOrUseDisconnectedAttackerOutpost() throws Exception {
        String id = active(); claim("b", 0, 1); rejects(wars.capture(key(id, "a", 0, 0), attacker));
        update("DELETE FROM claims WHERE x=0 AND z=1");
        update("DELETE FROM claims WHERE x=-2 AND z=0"); rejects(wars.capture(key(id, "a", 0, 0), attacker)); noCapture();
    }
    @Test void scoreAuditFailureRollsBackClaimAndCaptureJournal() throws Exception {
        String id = active();
        update("CREATE TEMP TRIGGER fail_audit BEFORE INSERT ON audit WHEN NEW.action='war-capture' BEGIN SELECT RAISE(ABORT,'simulated'); END");
        rejects(wars.capture(key(id, "a", 0, 0), attacker)); noCapture();
    }
    @Test void quotaIsThreePerSidePerWindowAndOldProgressCannotExecuteNextDay() throws Exception {
        String id = active();
        for (int x = 0; x < 3; x++) await(wars.capture(key(id, "a", x, 0), attacker));
        CaptureKey stale = key(id, "a", 3, 0); rejects(wars.capture(stale, attacker));
        clock.addAndGet(DAY); rejects(wars.capture(stale, attacker));
        await(wars.capture(key(id, "a", 3, 0), attacker));
        assertEquals(80, count("SELECT attack_score FROM wars"));
    }
    @Test void overnightQuotaCannotResetAtMidnight() throws Exception {
        config.warHourMoscow = 23; config.warWindowHours = 2;
        String id = declare(); clock.set(Instant.parse("2026-06-01T20:59:59Z").toEpochMilli());
        for (int x = 0; x < 3; x++) await(wars.capture(key(id, "a", x, 0), attacker));
        clock.addAndGet(1000); rejects(wars.capture(key(id, "a", 3, 0), attacker));
        assertEquals(3, count("SELECT COUNT(*) FROM war_captures"));
    }
    @Test void peaceNeedsMutualConsentDoesNotExtendOnReplayAndCancelsPendingCapture() throws Exception {
        String id = active(); CaptureKey pending = key(id, "a", 0, 0);
        await(wars.peace(attacker, "Bravo")); long expiry = count("SELECT expires FROM war_peace_offers");
        clock.addAndGet(1000); await(wars.peace(attacker, "Bravo"));
        assertEquals(expiry, count("SELECT expires FROM war_peace_offers")); assertEquals("ACTIVE", text("SELECT status FROM wars"));
        await(wars.peace(defender, "Alpha")); assertEquals("PEACE", text("SELECT status FROM wars"));
        assertEquals(clock.get(), count("SELECT ends FROM wars")); assertEquals(0, count("SELECT COUNT(*) FROM war_peace_offers"));
        rejects(wars.capture(pending, attacker)); rejects(wars.peace(defender, "Alpha")); noCapture();
    }
    @Test void expiredPeaceOfferNeedsFreshConsentAndOutsidersCannotAccept() throws Exception {
        active(); await(wars.peace(attacker, "Bravo")); clock.addAndGet(600_000);
        await(wars.peace(defender, "Alpha")); assertEquals("b", text("SELECT offered_by FROM war_peace_offers"));
        rejects(wars.peace(outsider, "Alpha")); assertEquals("ACTIVE", text("SELECT status FROM wars"));
        await(wars.peace(attacker, "Bravo")); assertEquals("PEACE", text("SELECT status FROM wars"));
    }
    @Test void peaceAndSurrenderRequireWarPermission() throws Exception {
        active(); UUID member = UUID.randomUUID();
        update("INSERT INTO members(uuid,nation,rank,joined) VALUES(?,'a','MEMBER',?)", member.toString(), clock.get());
        rejects(wars.peace(member, "Bravo")); rejects(wars.surrender(member, "Bravo"));
        assertEquals("ACTIVE", text("SELECT status FROM wars"));
    }
    @Test void surrenderEndsOnlyCorrectWarWithoutAdditionalLossAndStartsActualEndImmunity() throws Exception {
        declare(); long funds = treasury();
        rejects(wars.surrender(attacker, "Charlie")); await(wars.surrender(attacker, "Bravo"));
        assertEquals("DEFENDER_WIN", text("SELECT status FROM wars")); assertEquals(funds, treasury());
        assertEquals(9, count("SELECT COUNT(*) FROM claims"));
        rejects(wars.surrender(attacker, "Bravo")); rejects(wars.declare(attacker, "Bravo")); rejects(wars.declare(defender, "Alpha"));
        config.warImmunityDays = 5; clock.addAndGet(5 * DAY); rejects(wars.declare(attacker, "Bravo"));
        clock.addAndGet(2 * DAY); await(wars.declare(attacker, "Bravo")); assertEquals(2, count("SELECT COUNT(*) FROM wars"));
    }
    @Test void settlementUsesLatestScoresIsIdempotentAndCannotSettleEarly() throws Exception {
        String id = active(); await(wars.settle(id)); assertEquals("ACTIVE", text("SELECT status FROM wars"));
        update("UPDATE wars SET attack_score=20,defend_score=60");
        clock.set(count("SELECT ends FROM wars"));
        List<CompletableFuture<Void>> attempts = new ArrayList<>(); for (int i = 0; i < 10; i++) attempts.add(wars.settle(id));
        for (var attempt : attempts) await(attempt);
        assertEquals("DEFENDER_WIN", text("SELECT status FROM wars"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit WHERE action='war-settle'"));
        rejects(wars.capture(key(id, "a", 0, 0), attacker));
    }
    @Test void finishAuditFailureRollsBackStatusEndAndOffer() throws Exception {
        active(); await(wars.peace(attacker, "Bravo")); long ends = count("SELECT ends FROM wars");
        update("CREATE TEMP TRIGGER fail_finish BEFORE INSERT ON audit WHEN NEW.action='war-peace' BEGIN SELECT RAISE(ABORT,'simulated'); END");
        rejects(wars.peace(defender, "Alpha"));
        assertEquals("ACTIVE", text("SELECT status FROM wars")); assertEquals(ends, count("SELECT ends FROM wars"));
        assertEquals(1, count("SELECT COUNT(*) FROM war_peace_offers"));
    }
    @Test void restartRestoresImmutableScheduleCapturesOffersAndSettlesExpiredWars() throws Exception {
        String id = active(); await(wars.capture(key(id, "a", 0, 0), attacker)); await(wars.peace(attacker, "Bravo"));
        db.close(); db = new Store(dir.resolve("war.db")); await(db.start());
        config.warHourMoscow = 1; wars = new WarRepository(db, config, clock::get); await(wars.initialize());
        assertEquals(new Schedule(19, 2), await(wars.snapshot()).schedules().get(id));
        assertEquals(1, count("SELECT COUNT(*) FROM war_captures")); assertEquals(1, count("SELECT COUNT(*) FROM war_peace_offers"));
        clock.set(count("SELECT ends FROM wars") + DAY); await(wars.initialize());
        assertEquals("ATTACKER_WIN", text("SELECT status FROM wars")); assertEquals(0, count("SELECT COUNT(*) FROM war_peace_offers"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit WHERE action='war-settle'"));
    }
}
