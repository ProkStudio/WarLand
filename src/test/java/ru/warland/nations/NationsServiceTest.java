package ru.warland.nations;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.core.GameConfig;
import ru.warland.data.Store;
import static org.junit.jupiter.api.Assertions.*;

/** Domain integration tests: real temporary SQLite, no Minecraft world or player data. */
@Timeout(20)
class NationsServiceTest {
    private static final String WORLD = "minecraft:overworld";
    private static final long ACCOUNT_LIMIT = 1_000_000_000_000L;
    @TempDir Path dir;
    private Store store;
    private GameConfig config;
    private NationsService nations;
    private UUID owner, guest;

    @BeforeEach void open() throws Exception {
        store = new Store(dir.resolve("nations.db"));
        await(store.start());
        config = new GameConfig();
        nations = new NationsService(store, config);
        await(nations.refresh());
        owner = profile(true, 5000);
        guest = profile(true, 5000);
    }

    @AfterEach void close() { if (store != null) store.close(); }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private UUID profile(boolean tutorial, long funds) throws Exception {
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        await(store.tx(c -> {
            Store.update(c, "INSERT INTO profiles(uuid,name,joined,tutorial,last_seen) VALUES(?,?,?,?,?)",
                    id.toString(), "Synthetic", now - 86400000L, tutorial ? 1 : 0, now);
            Store.change(c, Store.player(id), funds, "fixture-" + id, "fixture");
            return null;
        }));
        return id;
    }

    private String found(UUID player, String name, int chunkX, int chunkZ) throws Exception {
        await(nations.create(player, name, WORLD, chunkX * 16 + 1, 64, chunkZ * 16 + 1));
        return nations.nation(player).id();
    }

    private void join(UUID player) throws Exception {
        await(nations.invite(owner, player));
        await(nations.join(player, nations.nation(owner).name()));
    }

    private long scalar(String sql, Object... params) throws Exception {
        return await(store.submit(c -> Store.scalar(c, sql, params)));
    }

    private String string(String sql, Object... params) throws Exception {
        return await(store.submit(c -> Store.string(c, sql, params)));
    }

    private void update(String sql, Object... params) throws Exception {
        await(store.tx(c -> Store.update(c, sql, params)));
    }

    private long wallet(UUID player) throws Exception { return await(store.balance(player)); }
    private long treasury(String nation) throws Exception {
        return scalar("SELECT balance FROM accounts WHERE owner=?", Store.nation(nation));
    }

    private SQLException rejects(CompletableFuture<?> future) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(future));
        return assertInstanceOf(SQLException.class, error.getCause());
    }

    private long successes(List<CompletableFuture<String>> futures) throws Exception {
        long count = 0;
        for (CompletableFuture<String> future : futures) {
            try { await(future); count++; }
            catch (ExecutionException e) { assertInstanceOf(SQLException.class, e.getCause()); }
        }
        return count;
    }

    private void activeWar(String attacker, String defender) throws Exception {
        update("INSERT INTO wars(id,attacker,defender,declared,starts,ends,status) VALUES(?,?,?,0,0,?,'ACTIVE')",
                UUID.randomUUID().toString(), attacker, defender, Long.MAX_VALUE);
    }

    @Test void initialSnapshotIsEmptyAndOutsidersHaveNoPermissions() {
        assertTrue(nations.snapshot().nations().isEmpty());
        assertNull(nations.member(owner));
        assertNull(nations.nation(owner));
        assertNull(nations.claim(WORLD, 0, 0));
        assertFalse(nations.allowed(owner, "missing", "build"));
    }

    @Test void foundingAtomicallyCreatesLeaderTreasuryAndNegativeCapitalChunk() throws Exception {
        await(nations.create(owner, "Север_01", WORLD, -1, 70, -17));
        var nation = nations.nation(owner);
        assertNotNull(nation);
        assertEquals("Север_01", nation.name());
        assertEquals(owner.toString(), nation.owner());
        assertEquals(-1, nation.x());
        assertEquals(-17, nation.z());
        assertEquals(70, nation.y());
        assertEquals(WORLD, nation.dimension());
        assertEquals("LEADER", nations.member(owner).rank());
        assertEquals(nation.id(), nations.claim(WORLD, -1, -2));
        assertEquals(5000 - config.nationCost, wallet(owner));
        assertEquals(0, treasury(nation.id()));
        assertEquals(1, scalar("SELECT COUNT(*) FROM ledger WHERE reason='nation-create'"));
        for (String permission : NationsService.defaults().get("LEADER"))
            assertTrue(nations.allowed(owner, nation.id(), permission));
        assertFalse(nations.allowed(guest, nation.id(), "build"));
    }

    @Test void invalidNamesNeverChargeOrCreateState() throws Exception {
        for (String name : new String[]{null, "ab", "two words", "<script>", "a".repeat(21)}) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> await(nations.create(owner, name, WORLD, 0, 64, 0)));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }
        assertEquals(5000, wallet(owner));
        assertEquals(0, scalar("SELECT COUNT(*) FROM nations"));
        assertTrue(nations.snapshot().claims().isEmpty());
    }

    @Test void foundingRequiresProfileTutorialAndSufficientFunds() throws Exception {
        UUID untrained = profile(false, 5000);
        UUID poor = profile(true, config.nationCost - 1);
        rejects(nations.create(UUID.randomUUID(), "Missing", WORLD, 0, 64, 0));
        rejects(nations.create(untrained, "Untrained", WORLD, 0, 64, 0));
        rejects(nations.create(poor, "Poorland", WORLD, 0, 64, 0));
        assertEquals(5000, wallet(untrained));
        assertEquals(config.nationCost - 1, wallet(poor));
        assertEquals(0, scalar("SELECT COUNT(*) FROM nations"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger WHERE reason='nation-create'"));
    }

    @Test void namesAreAsciiCaseInsensitiveAndMembershipIsUnique() throws Exception {
        found(owner, "Alpha", 0, 0);
        rejects(nations.create(guest, "aLpHa", WORLD, 160, 64, 0));
        rejects(nations.create(owner, "Another", WORLD, 160, 64, 0));
        assertEquals(5000, wallet(guest));
        assertEquals(4000, wallet(owner));
        assertEquals(1, nations.snapshot().nations().size());
    }

    @Test void foundingKeepsOneEmptyChunkIncludingDiagonals() throws Exception {
        found(owner, "Alpha", 0, 0);
        rejects(nations.create(guest, "Bravo", WORLD, 16, 64, 16));
        assertEquals(5000, wallet(guest));
        String other = found(guest, "Bravo", 2, 0);
        assertEquals(other, nations.claim(WORLD, 2, 0));
    }

    @Test void serviceRejectsFoundingOutsideOverworld() throws Exception {
        for (String dimension : List.of("minecraft:the_nether", "minecraft:the_end"))
            rejects(nations.create(owner, "Forbidden", dimension, 0, 64, 0));
        assertEquals(5000, wallet(owner));
        assertEquals(0, scalar("SELECT COUNT(*) FROM nations"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM claims"));
    }

    @Test void concurrentFoundingByOnePlayerChargesOnlyOnce() throws Exception {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 12; i++) futures.add(nations.create(owner, "Alpha", WORLD, i * 32, 64, 0));
        assertEquals(1, successes(futures));
        assertEquals(4000, wallet(owner));
        assertEquals(1, scalar("SELECT COUNT(*) FROM nations"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM claims"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM members"));
    }

    @Test void concurrentDuplicateNamesAcrossPlayersHaveOnlyOneWinner() throws Exception {
        var a = nations.create(owner, "Alpha", WORLD, 0, 64, 0);
        var b = nations.create(guest, "alpha", WORLD, 160, 64, 0);
        assertEquals(1, successes(List.of(a, b)));
        assertEquals(9000, wallet(owner) + wallet(guest));
        assertEquals(1, scalar("SELECT COUNT(*) FROM nations"));
    }

    @Test void invitationsRequirePermissionAndRejectExistingMembers() throws Exception {
        found(owner, "Alpha", 0, 0);
        join(guest);
        UUID outsider = profile(true, 100);
        rejects(nations.invite(guest, outsider));
        rejects(nations.invite(outsider, UUID.randomUUID()));
        rejects(nations.invite(owner, guest));
        rejects(nations.invite(owner, owner));
        assertEquals(0, scalar("SELECT COUNT(*) FROM invites"));
    }

    @Test void expiredInvitationsFailAndJoiningConsumesAllInvitations() throws Exception {
        String alpha = found(owner, "Alpha", 0, 0);
        UUID otherLeader = profile(true, 5000);
        found(otherLeader, "Bravo", 10, 0);
        await(nations.invite(owner, guest));
        await(nations.invite(otherLeader, guest));
        update("UPDATE invites SET expires=0 WHERE nation=?", alpha);
        rejects(nations.join(guest, "Alpha"));
        await(nations.invite(owner, guest));
        await(nations.join(guest, "aLpHa"));
        assertEquals(alpha, nations.member(guest).nation());
        assertEquals("MEMBER", nations.member(guest).rank());
        assertEquals(0, scalar("SELECT COUNT(*) FROM invites WHERE uuid=?", guest.toString()));
    }

    @Test void joiningRequiresExistingNationAndAnInvitation() throws Exception {
        found(owner, "Alpha", 0, 0);
        rejects(nations.join(guest, "Missing"));
        rejects(nations.join(guest, "Alpha"));
        join(guest);
        rejects(nations.join(guest, "Alpha"));
        assertEquals(2, nations.snapshot().members().size());
    }

    @Test void leaderCannotLeaveAndMemberLosesPermissionsOnLeaving() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        rejects(nations.leave(owner));
        rejects(nations.leave(guest));
        join(guest);
        assertTrue(nations.allowed(guest, id, "build"));
        await(nations.leave(guest));
        assertNull(nations.member(guest));
        assertFalse(nations.allowed(guest, id, "build"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM claims"));
    }

    @Test void claimsRequireTreasuryOverworldAndEdgeAdjacency() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        rejects(nations.claim(owner, WORLD, 1, 0));
        await(nations.transfer(owner, 400, false));
        rejects(nations.claim(owner, WORLD, 1, 1));
        rejects(nations.claim(owner, WORLD, 3, 0));
        rejects(nations.claim(owner, "minecraft:the_nether", 1, 0));
        await(nations.claim(owner, WORLD, 1, 0));
        assertEquals(id, nations.claim(WORLD, 1, 0));
        assertEquals(300, treasury(id));
        assertEquals(2, nations.snapshot().claims().size());
        assertEquals(1, scalar("SELECT COUNT(*) FROM ledger WHERE reason='claim'"));
    }

    @Test void concurrentClaimOfOneChunkChargesOnlyOnce() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        await(nations.transfer(owner, 1000, false));
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 12; i++) futures.add(nations.claim(owner, WORLD, 1, 0));
        assertEquals(1, successes(futures));
        assertEquals(900, treasury(id));
        assertEquals(2, scalar("SELECT COUNT(*) FROM claims WHERE nation=?", id));
        assertEquals(1, scalar("SELECT COUNT(*) FROM ledger WHERE reason='claim'"));
    }

    @Test void claimPermissionsAndForeignOwnershipCannotBeBypassed() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        UUID otherLeader = profile(true, 5000);
        String other = found(otherLeader, "Bravo", 2, 0);
        join(guest);
        await(nations.transfer(owner, 500, false));
        rejects(nations.claim(guest, WORLD, 1, 0));
        rejects(nations.claim(owner, WORLD, 2, 0));
        await(nations.rank(owner, guest, "OFFICER"));
        await(nations.claim(guest, WORLD, 1, 0));
        assertEquals(id, nations.claim(WORLD, 1, 0));
        assertEquals(other, nations.claim(WORLD, 2, 0));
        assertFalse(nations.allowed(guest, other, "build"));
        assertFalse(nations.allowed(otherLeader, id, "claim"));
    }

    @Test void claimLimitCountsCapitalAndCityLevelBonus() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        await(nations.transfer(owner, 3000, false));
        for (int x = 1; x <= 11; x++) await(nations.claim(owner, WORLD, x, 0));
        rejects(nations.claim(owner, WORLD, 12, 0));
        assertEquals(12, scalar("SELECT COUNT(*) FROM claims WHERE nation=?", id));
        update("UPDATE nations SET level=1 WHERE id=?", id);
        await(nations.claim(owner, WORLD, 12, 0));
        assertEquals(13, scalar("SELECT COUNT(*) FROM claims WHERE nation=?", id));
    }

    @Test void residentsLastSeenOverSevenDaysAgoDoNotIncreaseLimit() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        update("UPDATE profiles SET last_seen=? WHERE uuid=?", System.currentTimeMillis() - 8 * 86400000L, owner.toString());
        await(nations.transfer(owner, 2000, false));
        for (int x = 1; x <= 8; x++) await(nations.claim(owner, WORLD, x, 0));
        rejects(nations.claim(owner, WORLD, 9, 0));
        assertEquals(9, scalar("SELECT COUNT(*) FROM claims WHERE nation=?", id));
    }

    @Test void activeWarFreezesClaimsAndMembershipForBothSides() throws Exception {
        String alpha = found(owner, "Alpha", 0, 0);
        UUID otherLeader = profile(true, 5000);
        String bravo = found(otherLeader, "Bravo", 10, 0);
        join(guest);
        UUID recruit = profile(true, 100);
        await(nations.invite(owner, recruit));
        await(nations.transfer(owner, 300, false));
        await(nations.transfer(otherLeader, 300, false));
        activeWar(alpha, bravo);
        rejects(nations.join(recruit, "Alpha"));
        rejects(nations.leave(guest));
        rejects(nations.claim(owner, WORLD, 1, 0));
        rejects(nations.claim(otherLeader, WORLD, 11, 0));
        update("UPDATE wars SET status='DONE'");
        await(nations.join(recruit, "Alpha"));
        await(nations.leave(guest));
        await(nations.claim(owner, WORLD, 1, 0));
        await(nations.claim(otherLeader, WORLD, 11, 0));
    }

    @Test void ordinaryMemberMayDepositButCannotWithdraw() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        join(guest);
        await(nations.transfer(guest, 500, false));
        rejects(nations.transfer(guest, 1, true));
        rejects(nations.transfer(UUID.randomUUID(), 1, false));
        await(nations.transfer(owner, 100, true));
        assertEquals(4500, wallet(guest));
        assertEquals(4100, wallet(owner));
        assertEquals(400, treasury(id));
        assertEquals(0, scalar("SELECT SUM(delta) FROM ledger WHERE reason='treasury'"));
    }

    @Test void treasuryRejectsZeroNegativeAndOversizedAmounts() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        long ledgerBefore = scalar("SELECT COUNT(*) FROM ledger");
        for (long amount : new long[]{Long.MIN_VALUE, -1, 0, 1_000_000_001L, Long.MAX_VALUE}) {
            rejects(nations.transfer(owner, amount, false));
            rejects(nations.transfer(owner, amount, true));
        }
        assertEquals(4000, wallet(owner));
        assertEquals(0, treasury(id));
        assertEquals(ledgerBefore, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void insufficientTreasuryOrWalletLeavesBothBalancesUnchanged() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        await(nations.transfer(owner, 100, false));
        long ledgerBefore = scalar("SELECT COUNT(*) FROM ledger");
        rejects(nations.transfer(owner, 101, true));
        rejects(nations.transfer(owner, 3901, false));
        assertEquals(3900, wallet(owner));
        assertEquals(100, treasury(id));
        assertEquals(ledgerBefore, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void recipientAccountCapRollsBackDebitInBothDirections() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        await(store.tx(c -> Store.change(c, Store.nation(id), ACCOUNT_LIMIT, "fixture-treasury", "fixture")));
        long ledgerBefore = scalar("SELECT COUNT(*) FROM ledger");
        rejects(nations.transfer(owner, 1, false));
        assertEquals(4000, wallet(owner));
        assertEquals(ACCOUNT_LIMIT, treasury(id));
        assertEquals(ledgerBefore, scalar("SELECT COUNT(*) FROM ledger"));
        await(store.money(owner, ACCOUNT_LIMIT - 4000, "fixture-wallet", "fixture"));
        ledgerBefore = scalar("SELECT COUNT(*) FROM ledger");
        rejects(nations.transfer(owner, 1, true));
        assertEquals(ACCOUNT_LIMIT, wallet(owner));
        assertEquals(ACCOUNT_LIMIT, treasury(id));
        assertEquals(ledgerBefore, scalar("SELECT COUNT(*) FROM ledger"));
    }

    @Test void concurrentWithdrawalsNeverOverdrawTreasury() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        await(nations.transfer(owner, 100, false));
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) futures.add(nations.transfer(owner, 2, true));
        assertEquals(50, successes(futures));
        assertEquals(0, treasury(id));
        assertEquals(4000, wallet(owner));
        assertEquals(0, scalar("SELECT SUM(delta) FROM ledger WHERE reason='treasury'"));
    }

    @Test void customRankGrantsOnlyItsConfiguredPermissions() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        join(guest);
        await(nations.defineRank(owner, "TREASURER", "build,treasury,build"));
        await(nations.rank(owner, guest, "TREASURER"));
        assertTrue(nations.allowed(guest, id, "treasury"));
        assertTrue(nations.allowed(guest, id, "build"));
        assertFalse(nations.allowed(guest, id, "claim"));
        await(nations.transfer(owner, 400, false));
        await(nations.transfer(guest, 100, true));
        assertEquals(5100, wallet(guest));
        assertEquals(300, treasury(id));
    }

    @Test void rankValidationProtectsLeaderAndRejectsUnknownRights() throws Exception {
        found(owner, "Alpha", 0, 0);
        join(guest);
        rejects(nations.rank(owner, owner, "MEMBER"));
        rejects(nations.rank(owner, guest, "LEADER"));
        rejects(nations.rank(owner, guest, "MISSING"));
        rejects(nations.rank(owner, UUID.randomUUID(), "OFFICER"));
        for (String rank : List.of("LEADER", "ab", "HAS SPACE", "a".repeat(21)))
            rejects(nations.defineRank(owner, rank, "build"));
        for (String permissions : List.of("", "build,owner", "build,,claim", "BUILD"))
            rejects(nations.defineRank(owner, "CUSTOM", permissions));
        assertEquals("LEADER", nations.member(owner).rank());
        assertEquals("MEMBER", nations.member(guest).rank());
    }

    @Test void allAssignablePermissionsStillCannotDelegateLeaderAuthority() throws Exception {
        found(owner, "Alpha", 0, 0);
        join(guest);
        await(nations.defineRank(owner, "REGENT", "build,claim,invite,treasury,war,buildings,diplomacy"));
        await(nations.rank(owner, guest, "REGENT"));
        rejects(nations.defineRank(guest, "CUSTOM", "build"));
        rejects(nations.rank(guest, owner, "MEMBER"));
        rejects(nations.tax(guest, 1));
        assertEquals(owner.toString(), nations.nation(guest).owner());
    }

    @Test void rankCapAllowsUpdatingExistingRankButNotAddingThirteenth() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        for (char suffix = 'A'; suffix <= 'H'; suffix++)
            await(nations.defineRank(owner, "RANK_" + suffix, "build"));
        assertEquals(12, nations.snapshot().ranks().get(id).size());
        rejects(nations.defineRank(owner, "EXTRA", "build"));
        await(nations.defineRank(owner, "RANK_A", "treasury"));
        assertEquals(Set.of("treasury"), nations.snapshot().ranks().get(id).get("RANK_A"));
    }

    @Test void permissionRevocationAffectsSnapshotAndTransactionalChecks() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        join(guest);
        await(nations.rank(owner, guest, "OFFICER"));
        assertTrue(nations.allowed(guest, id, "claim"));
        await(nations.transfer(owner, 300, false));
        await(nations.defineRank(owner, "OFFICER", "build"));
        assertFalse(nations.allowed(guest, id, "claim"));
        rejects(nations.claim(guest, WORLD, 1, 0));
        assertEquals(300, treasury(id));
    }

    @Test void taxesAreLeaderOnlyBoundedAndDelayedExactlyOneDay() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        join(guest);
        rejects(nations.tax(guest, 5));
        rejects(nations.tax(owner, -1));
        rejects(nations.tax(owner, config.maxTaxPercent + 1));
        long before = System.currentTimeMillis();
        await(nations.tax(owner, config.maxTaxPercent));
        long after = System.currentTimeMillis();
        long effective = scalar("SELECT effective_tax FROM nations WHERE id=?", id);
        assertTrue(effective >= before + 86400000L && effective <= after + 86400000L);
        assertEquals(0, nations.nation(owner).tax());
        await(nations.applyTaxes());
        assertEquals(0, nations.nation(owner).tax());
        update("UPDATE nations SET effective_tax=? WHERE id=?", before - 1, id);
        await(nations.applyTaxes());
        assertEquals(config.maxTaxPercent, nations.nation(owner).tax());
        assertNull(string("SELECT pending_tax FROM nations WHERE id=?", id));
        assertNull(string("SELECT effective_tax FROM nations WHERE id=?", id));
    }

    @Test void snapshotsAreDeeplyImmutableAndRemainStableAcrossRefresh() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        var snapshot = nations.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.nations().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.members().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.claims().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.ranks().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.ranks().get(id).clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.ranks().get(id).get("MEMBER").clear());
        found(guest, "Bravo", 10, 0);
        assertEquals(1, snapshot.nations().size());
        assertEquals(2, nations.snapshot().nations().size());
    }

    @Test void restartRestoresMembershipClaimsTreasuryAndCustomPermissions() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        join(guest);
        await(nations.defineRank(owner, "TREASURER", "build,treasury"));
        await(nations.rank(owner, guest, "TREASURER"));
        await(nations.transfer(owner, 300, false));
        await(nations.claim(owner, WORLD, 1, 0));
        store.close();
        store = new Store(dir.resolve("nations.db"));
        await(store.start());
        nations = new NationsService(store, config);
        await(nations.refresh());
        assertEquals(id, nations.nation(owner).id());
        assertEquals(id, nations.member(guest).nation());
        assertEquals("TREASURER", nations.member(guest).rank());
        assertEquals(id, nations.claim(WORLD, 1, 0));
        assertEquals(200, treasury(id));
        assertEquals(3700, wallet(owner));
        assertTrue(nations.allowed(guest, id, "treasury"));
    }

    @Test void pactRequiresMutualConfirmationAndCannotBeDowngradedByReplay() throws Exception {
        found(owner, "Alpha", 0, 0);
        found(guest, "Bravo", 10, 0);
        await(nations.pact(owner, "Bravo"));
        assertEquals("OFFER", string("SELECT state FROM diplomacy"));
        await(nations.pact(owner, "Bravo"));
        assertEquals("OFFER", string("SELECT state FROM diplomacy"));
        long before = System.currentTimeMillis();
        await(nations.pact(guest, "Alpha"));
        assertEquals("PACT", string("SELECT state FROM diplomacy"));
        long expiry = scalar("SELECT expires FROM diplomacy");
        assertTrue(expiry >= before + 7 * 86400000L);
        rejects(nations.pact(owner, "Bravo"));
        rejects(nations.pact(guest, "Alpha"));
        assertEquals("PACT", string("SELECT state FROM diplomacy"));
        assertEquals(expiry, scalar("SELECT expires FROM diplomacy"));
    }

    @Test void expiredPactNeedsNewMutualConsent() throws Exception {
        found(owner, "Alpha", 0, 0);
        found(guest, "Bravo", 10, 0);
        await(nations.pact(owner, "Bravo"));
        await(nations.pact(guest, "Alpha"));
        update("UPDATE diplomacy SET expires=0");
        await(nations.pact(owner, "Bravo"));
        assertEquals("OFFER", string("SELECT state FROM diplomacy"));
        await(nations.pact(guest, "Alpha"));
        assertEquals("PACT", string("SELECT state FROM diplomacy"));
    }

    @Test void pactRejectsSelfUnknownNationOutsidersAndActiveWar() throws Exception {
        String alpha = found(owner, "Alpha", 0, 0);
        String bravo = found(guest, "Bravo", 10, 0);
        rejects(nations.pact(owner, "Alpha"));
        rejects(nations.pact(owner, "Missing"));
        rejects(nations.pact(UUID.randomUUID(), "Alpha"));
        activeWar(alpha, bravo);
        rejects(nations.pact(owner, "Bravo"));
        rejects(nations.pact(guest, "Alpha"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM diplomacy"));
    }

    @Test void foundingSqlFailureRollsBackChargeMembershipTreasuryAndCache() throws Exception {
        update("CREATE TEMP TRIGGER fail_found BEFORE INSERT ON claims BEGIN SELECT RAISE(ABORT,'simulated claim failure'); END");
        rejects(nations.create(owner, "Alpha", WORLD, 0, 64, 0));
        assertEquals(5000, wallet(owner));
        assertEquals(0, scalar("SELECT COUNT(*) FROM nations"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM members"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM claims"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM accounts WHERE owner LIKE 'nation:%'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger WHERE reason='nation-create'"));
        assertTrue(nations.snapshot().nations().isEmpty());
    }

    @Test void claimSqlFailureRollsBackTreasuryAndPreservesCache() throws Exception {
        String id = found(owner, "Alpha", 0, 0);
        await(nations.transfer(owner, 500, false));
        update("CREATE TEMP TRIGGER fail_claim BEFORE INSERT ON claims WHEN NEW.x=1 BEGIN SELECT RAISE(ABORT,'simulated insert failure'); END");
        rejects(nations.claim(owner, WORLD, 1, 0));
        assertEquals(500, treasury(id));
        assertEquals(1, scalar("SELECT COUNT(*) FROM claims"));
        assertEquals(1, nations.snapshot().claims().size());
        assertEquals(0, scalar("SELECT COUNT(*) FROM ledger WHERE reason='claim'"));
    }
}
