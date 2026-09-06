package ru.warland.auth;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class AuthTest {
    @TempDir Path dir;
    Store store; AuthRepository repo; Authentication auth;
    static Passwords.Hash hash;
    static final String TOKEN = "A".repeat(43);
    static final long NOW = 1_000;
    UUID player = UUID.randomUUID();
    @BeforeAll static void fixtureHash() { hash = Passwords.hash(password()); }
    static char[] password() { return "WarLand synthetic passphrase".toCharArray(); }
    @BeforeEach void start() throws Exception {
        store = new Store(dir.resolve("auth.db")); store.start().get();
        repo = new AuthRepository(store); repo.start().get(); auth = new Authentication(repo);
    }
    @AfterEach void stop() { auth.close(); store.close(); }
    void rejected(CompletableFuture<?> future) { assertThrows(CompletionException.class, future::join); }
    void bootstrap() throws Exception { repo.installOwnerChallenge(Authentication.tokenDigest(TOKEN), NOW + 1000, NOW).get(); }

    @Test void saltedKdfAcceptsOnlyExactPassword() {
        assertTrue(Passwords.verify(password(), hash));
        assertFalse(Passwords.verify("WarLand wrong passphrase".toCharArray(), hash));
        assertNotEquals(hash.encoded(), Passwords.hash(password()).encoded());
        assertFalse(hash.toString().contains(hash.encoded()));
    }
    @Test void corruptOrAttackerControlledKdfParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Passwords.Hash(hash.encoded().replace("600000", "1")));
        assertThrows(IllegalArgumentException.class, () -> new Passwords.Hash(hash.encoded().replace("600000", "2147483647")));
        assertThrows(IllegalArgumentException.class, () -> new Passwords.Hash("plaintext-password"));
        assertThrows(IllegalArgumentException.class, () -> Passwords.hash("short".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> Passwords.hash(new char[129]));
    }
    @Test void passwordLengthsAndUnicodePassphrasesAreSupported() {
        char[] unicode = "длинный пароль с пробелами".toCharArray();
        assertTrue(Passwords.verify(unicode, Passwords.hash(unicode)));
        assertFalse(Passwords.verify(null, hash)); assertFalse(Passwords.verify(new char[129], hash));
    }
    @Test void accountRequiresBothBoundUuidAndCanonicalName() throws Exception {
        repo.register(player, "TestUser", hash, null, NOW).get();
        assertNotNull(repo.account(player, "TESTUSER").get());
        assertNull(repo.account(UUID.randomUUID(), "TestUser").get());
        assertNull(repo.account(player, "AnotherUser").get());
        rejected(repo.register(UUID.randomUUID(), "testuser", hash, null, NOW));
        rejected(repo.register(player, "AnotherUser", hash, null, NOW));
    }
    @Test void invalidNamesCannotCreateAccounts() {
        for (String name : List.of("a", "abcdefghijklmnopq", "../owner", "тест", "name space"))
            rejected(repo.register(player, name, hash, null, NOW));
    }
    @Test void reservedOwnerCannotRegisterWithoutPrivateProof() throws Exception {
        rejected(repo.register(player, "egorkrid666", hash, null, NOW));
        assertNull(repo.owner().get()); bootstrap();
        rejected(repo.register(player, "EGORKRID666", hash, "b".repeat(64), NOW));
        assertNull(repo.account(player, "egorkrid666").get());
    }
    @Test void ownerProofIsConsumedAtomicallyAndBoundToUuid() throws Exception {
        bootstrap(); repo.register(player, "EGORKRID666", hash, Authentication.tokenDigest(TOKEN), NOW).get();
        assertEquals(player, repo.owner().get());
        assertNull(store.submit(c -> Store.string(c, "SELECT challenge_hash FROM auth_owner WHERE id=1")).get());
        rejected(repo.register(UUID.randomUUID(), "egorkrid666", hash, Authentication.tokenDigest(TOKEN), NOW));
        rejected(repo.installOwnerChallenge("b".repeat(64), NOW + 1000, NOW));
    }
    @Test void proofCannotClaimAnUnreservedNameOrExpiredReservation() throws Exception {
        bootstrap();
        rejected(repo.register(player, "DifferentName", hash, Authentication.tokenDigest(TOKEN), NOW));
        rejected(repo.register(player, "egorkrid666", hash, Authentication.tokenDigest(TOKEN), NOW + 1000));
        assertNull(repo.owner().get());
    }
    @Test void rotatingUnclaimedChallengeInvalidatesOldProof() throws Exception {
        bootstrap(); String replacement = Authentication.tokenDigest("B".repeat(43));
        repo.installOwnerChallenge(replacement, NOW + 1000, NOW).get();
        rejected(repo.register(player, "egorkrid666", hash, Authentication.tokenDigest(TOKEN), NOW));
        repo.register(player, "egorkrid666", hash, replacement, NOW).get(); assertEquals(player, repo.owner().get());
    }
    @Test void failedAuditRollsBackOwnerClaimAndAccountTogether() throws Exception {
        bootstrap();
        store.tx(c -> Store.update(c, "CREATE TRIGGER fail_auth_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'test'); END")).get();
        rejected(repo.register(player, "egorkrid666", hash, Authentication.tokenDigest(TOKEN), NOW));
        assertNull(repo.owner().get()); assertNull(repo.account(player, "egorkrid666").get());
        assertNotNull(store.submit(c -> Store.string(c, "SELECT challenge_hash FROM auth_owner WHERE id=1")).get());
        store.tx(c -> Store.update(c, "DROP TRIGGER fail_auth_audit")).get();
        repo.register(player, "egorkrid666", hash, Authentication.tokenDigest(TOKEN), NOW).get();
    }
    @Test void restartPreservesAccountAndOwnerWithoutRegranting() throws Exception {
        bootstrap(); repo.register(player, "egorkrid666", hash, Authentication.tokenDigest(TOKEN), NOW).get();
        auth.close(); store.close(); store = new Store(dir.resolve("auth.db")); store.start().get();
        repo = new AuthRepository(store); repo.start().get(); auth = new Authentication(repo);
        assertEquals(player, repo.owner().get()); assertEquals(hash, repo.account(player, "egorkrid666").get().password());
        assertFalse(auth.owner(auth.open(player, "egorkrid666", "loopback")).get());
    }
    @Test void credentialRevisionUsesCompareAndSwap() throws Exception {
        repo.register(player, "TestUser", hash, null, NOW).get(); assertTrue(repo.current(player, 1).get());
        assertTrue(repo.changePassword(player, 1, hash, NOW).get());
        assertFalse(repo.current(player, 1).get()); assertFalse(repo.changePassword(player, 1, hash, NOW).get());
        assertEquals(2, repo.account(player, "TestUser").get().revision());
    }
    @Test void futureSchemaAndDifferentReservedOwnerFailClosed() throws Exception {
        repo.register(player, "TestUser", hash, null, NOW).get();
        rejected(new AuthRepository(store, "SomeoneElse").start());
        store.tx(c -> Store.update(c, "UPDATE auth_schema SET version=2 WHERE id=1")).get();
        rejected(repo.start()); assertNotNull(repo.account(player, "TestUser").get());
    }
    @Test void registrationAuthenticatesOnlyCurrentConnectionAndClearsInput() throws Exception {
        var c = auth.open(player, "TestUser", "127.0.0.1"); char[] input = password();
        assertEquals(Authentication.Result.SUCCESS, auth.register(c, input, null, NOW).get());
        assertArrayEquals(new char[input.length], input); assertTrue(auth.authenticated(c));
        auth.disconnect(c); assertFalse(auth.authenticated(c));
        var next = auth.open(player, "TestUser", "127.0.0.1");
        assertEquals(Authentication.Result.SUCCESS, auth.login(next, password()).get()); assertTrue(auth.authenticated(next));
    }
    @Test void wrongPasswordAndUnknownAccountShareGenericDenial() throws Exception {
        repo.register(player, "TestUser", hash, null, NOW).get();
        var known = auth.open(player, "TestUser", "127.0.0.1");
        char[] wrong = "incorrect test password".toCharArray();
        assertEquals(Authentication.Result.DENIED, auth.login(known, wrong).get()); assertArrayEquals(new char[wrong.length], wrong);
        var unknown = auth.open(UUID.randomUUID(), "UnknownUser", "127.0.0.2");
        assertEquals(Authentication.Result.DENIED, auth.login(unknown, password()).get());
        assertFalse(auth.authenticated(known)); assertFalse(auth.authenticated(unknown));
    }
    @Test void ownerPrivilegeRequiresProofAndCurrentAuthenticatedSession() throws Exception {
        bootstrap(); var c = auth.open(player, "egorkrid666", "loopback");
        assertFalse(auth.owner(c).get());
        assertEquals(Authentication.Result.SUCCESS, auth.register(c, password(), TOKEN, NOW).get());
        assertTrue(auth.owner(c).get());
        var next = auth.open(player, "egorkrid666", "loopback");
        assertFalse(auth.owner(c).get()); assertFalse(auth.owner(next).get());
    }
    @Test void lateVerificationCannotAuthenticateReplacementConnection() throws Exception {
        repo.register(player, "TestUser", hash, null, NOW).get();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var block = store.submit(c -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var old = auth.open(player, "TestUser", "loopback"); var login = auth.login(old, password());
        var next = auth.open(player, "TestUser", "loopback"); release.countDown(); block.get();
        assertEquals(Authentication.Result.DENIED, login.get());
        assertFalse(auth.authenticated(next)); auth.disconnect(old); assertFalse(auth.authenticated(next));
    }
    @Test void queuedAuthenticationFinishesDeniedOnShutdown() throws Exception {
        repo.register(player, "TestUser", hash, null, NOW).get();
        List<CompletableFuture<Authentication.Result>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) results.add(auth.login(auth.open(UUID.randomUUID(), "Unknown" + i, "peer" + i), password()));
        auth.close();
        for (var result : results) assertEquals(Authentication.Result.DENIED, result.get(10, TimeUnit.SECONDS));
    }
    @Test void connectionDeadlineAndOldDisconnectCannotExtendLogin() {
        AtomicLong clock = new AtomicLong(); Admission a = new Admission(clock::get);
        UUID old = a.open(player), next = a.open(player); a.close(player, old);
        assertFalse(a.authenticate(player, old)); assertTrue(a.pending(player, next));
        clock.set(TimeUnit.SECONDS.toNanos(120)); assertFalse(a.authenticate(player, next));
        UUID current = a.open(player); assertTrue(a.authenticate(player, current));
        clock.addAndGet(TimeUnit.HOURS.toNanos(8)); assertFalse(a.authenticated(player, current));
    }
    @Test void successfulCallbackReplayDoesNotExtendSessionLifetime() {
        AtomicLong clock = new AtomicLong(); Admission a = new Admission(clock::get); UUID nonce = a.open(player);
        a.authenticate(player, nonce); clock.set(TimeUnit.HOURS.toNanos(7)); assertTrue(a.authenticate(player, nonce));
        clock.set(TimeUnit.HOURS.toNanos(8)); assertFalse(a.authenticated(player, nonce));
    }
    @Test void pendingConnectionCapacityIsBoundedAndExpires() {
        AtomicLong clock = new AtomicLong(); Admission a = new Admission(clock::get);
        for (int i = 0; i < 128; i++) a.open(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> a.open(UUID.randomUUID()));
        clock.set(TimeUnit.SECONDS.toNanos(120)); assertNotNull(a.open(UUID.randomUUID()));
    }
    @Test void throttleNormalizesNamesAndResetsAtExactWindow() {
        AtomicLong clock = new AtomicLong(); Admission.Throttle throttle = new Admission.Throttle(clock::get);
        for (int i = 0; i < 5; i++) assertTrue(throttle.acquire("TestUser", "peer" + i));
        assertFalse(throttle.acquire("TESTUSER", "anotherpeer"));
        clock.set(TimeUnit.MINUTES.toNanos(1)); assertTrue(throttle.acquire("testuser", "peer"));
    }
    @Test void throttleCapsPeerAndGlobalWorkDespiteChangingNames() {
        AtomicLong clock = new AtomicLong(); Admission.Throttle throttle = new Admission.Throttle(clock::get);
        for (int i = 0; i < 20; i++) assertTrue(throttle.acquire("User" + i, "onepeer"));
        assertFalse(throttle.acquire("AnotherUser", "onepeer"));
        for (int i = 20; i < 40; i++) assertTrue(throttle.acquire("User" + i, "peer" + i));
        assertFalse(throttle.acquire("ExtraUser", "newpeer"));
        assertFalse(throttle.acquire("ExtraUser", ""));
    }
    @Test void bootstrapExpiryAndFormatAreBounded() {
        rejected(repo.installOwnerChallenge("invalid", NOW + 1000, NOW));
        rejected(repo.installOwnerChallenge("a".repeat(64), NOW, NOW));
        rejected(repo.installOwnerChallenge("a".repeat(64), NOW + 86_400_001, NOW));
        assertThrows(IllegalArgumentException.class, () -> Authentication.tokenDigest("short"));
    }
}
