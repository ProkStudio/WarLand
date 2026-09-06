package ru.warland.auth;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Only synthetic credentials and fresh temporary databases; no live owner provisioning. */
@Timeout(30)
class AuthBootstrapExpiryTest {
    @TempDir Path dir;
    Store store;
    AuthRepository repo;
    static final long REQUESTED = 1_000, EXPIRES = 2_000;
    static final String DIGEST = "a".repeat(64), ORIGINAL = "b".repeat(64);
    static final String OWNER = AuthRepository.REQUESTED_OWNER;
    static final Passwords.Hash HASH = new Passwords.Hash(
            "wl-pbkdf2-sha256$600000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    final UUID player = UUID.randomUUID();
    final AtomicLong clock = new AtomicLong(REQUESTED);

    @BeforeEach void start() throws Exception {
        store = new Store(dir.resolve("bootstrap.db"));
        await(store.start()); repo = new AuthRepository(store); await(repo.start());
    }
    @AfterEach void stop() { store.close(); }
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    static void denied(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(future));
        assertInstanceOf(SQLException.class, failure.getCause());
    }
    void seed(String digest, long expires) throws Exception {
        await(store.tx(c -> Store.update(c,
                "UPDATE auth_owner SET challenge_hash=?,expires=? WHERE id=1", digest, expires)));
    }
    void unchanged(String digest, long expires) throws Exception {
        assertNull(await(repo.owner()));
        assertNull(await(repo.account(player, OWNER)));
        assertEquals(digest, await(store.submit(c -> Store.string(c,
                "SELECT challenge_hash FROM auth_owner WHERE id=1"))));
        long durableExpiry = await(store.submit(c -> Store.scalar(c, "SELECT expires FROM auth_owner WHERE id=1")));
        long accounts = await(store.submit(c -> Store.scalar(c, "SELECT COUNT(*) FROM auth_accounts")));
        long audit = await(store.submit(c -> Store.scalar(c, "SELECT COUNT(*) FROM audit WHERE action LIKE 'auth.%'")));
        assertEquals(expires, durableExpiry); assertEquals(0, accounts); assertEquals(0, audit);
    }

    @Test void defaultClockRejectsStaleRegistrationTimestamp() throws Exception {
        // Model a request that waited in KDF/Store past its already persisted deadline.
        seed(DIGEST, EXPIRES);
        denied(repo.register(player, OWNER, HASH, DIGEST, REQUESTED));
        unchanged(DIGEST, EXPIRES);
    }
    @Test void defaultClockRejectsStaleProvisioningWithoutReplacingExistingProof() throws Exception {
        seed(ORIGINAL, EXPIRES + 1_000);
        denied(repo.installOwnerChallenge(DIGEST, EXPIRES, REQUESTED));
        unchanged(ORIGINAL, EXPIRES + 1_000);
    }

    void useClock() { repo = new AuthRepository(store, OWNER, clock::get); }

    /** Latches model a DB-worker backlog without timing sleeps or a live server. */
    final class HeldStore implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Void> pending;
        HeldStore() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            pending = store.submit(c -> {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new TimeoutException("Synthetic worker wait");
                return null;
            });
            if (!entered.await(10, TimeUnit.SECONDS)) {
                release.countDown();
                fail("Synthetic worker did not start");
            }
        }
        @Override public void close() throws Exception { release.countDown(); await(pending); }
    }

    @Test void namedOwnerConstructorAlsoUsesCurrentClock() throws Exception {
        repo = new AuthRepository(store, OWNER);
        seed(DIGEST, EXPIRES);
        denied(repo.register(player, OWNER, HASH, DIGEST, REQUESTED));
        unchanged(DIGEST, EXPIRES);
    }
    @Test void queuedRegistrationExpiresBeforeDatabaseExecution() throws Exception {
        useClock(); seed(DIGEST, EXPIRES);
        CompletableFuture<AuthRepository.Account> registration;
        try (HeldStore held = new HeldStore()) {
            registration = repo.register(player, OWNER, HASH, DIGEST, REQUESTED);
            assertFalse(registration.isDone()); clock.set(EXPIRES);
        }
        denied(registration); unchanged(DIGEST, EXPIRES);
    }
    @Test void queuedProvisioningDoesNotReplacePreviousProofAfterItsOwnDeadline() throws Exception {
        useClock(); seed(ORIGINAL, EXPIRES + 1_000);
        CompletableFuture<Void> installation;
        try (HeldStore held = new HeldStore()) {
            installation = repo.installOwnerChallenge(DIGEST, EXPIRES, REQUESTED);
            assertFalse(installation.isDone()); clock.set(EXPIRES);
        }
        denied(installation); unchanged(ORIGINAL, EXPIRES + 1_000);
    }
    @Test void finalBindingExpiryRollsBackAccountAndSuccessfulAuditTogether() throws Exception {
        seed(DIGEST, EXPIRES); AtomicInteger reads = new AtomicInteger();
        repo = new AuthRepository(store, OWNER, () -> reads.incrementAndGet() == 1 ? REQUESTED : EXPIRES);
        denied(repo.register(player, OWNER, HASH, DIGEST, REQUESTED));
        assertTrue(reads.get() >= 2); unchanged(DIGEST, EXPIRES);
    }
    @Test void finalProvisioningExpiryRollsBackReplacementAndAuditTogether() throws Exception {
        seed(ORIGINAL, EXPIRES + 1_000); AtomicInteger reads = new AtomicInteger();
        repo = new AuthRepository(store, OWNER, () -> reads.incrementAndGet() == 1 ? REQUESTED : EXPIRES);
        denied(repo.installOwnerChallenge(DIGEST, EXPIRES, REQUESTED));
        assertTrue(reads.get() >= 2); unchanged(ORIGINAL, EXPIRES + 1_000);
    }
    @Test void exactExpiryAndLaterAreDeniedWithoutConsumingProof() throws Exception {
        useClock(); seed(DIGEST, EXPIRES);
        for (long instant : new long[] {EXPIRES, EXPIRES + 1}) {
            clock.set(instant);
            denied(repo.register(player, OWNER, HASH, DIGEST, REQUESTED));
            unchanged(DIGEST, EXPIRES);
        }
    }
    @Test void lastValidMillisecondSucceedsExactlyOnceWithExecutionTimestamp() throws Exception {
        useClock(); seed(DIGEST, EXPIRES); clock.set(EXPIRES - 1);
        assertEquals(player, await(repo.register(player, OWNER, HASH, DIGEST, REQUESTED)).player());
        assertEquals(player, await(repo.owner()));
        denied(repo.register(UUID.randomUUID(), OWNER, HASH, DIGEST, REQUESTED));
        assertNull(await(store.submit(c -> Store.string(c, "SELECT challenge_hash FROM auth_owner WHERE id=1"))));
        long remaining = await(store.submit(c -> Store.scalar(c, "SELECT expires FROM auth_owner WHERE id=1")));
        long accounts = await(store.submit(c -> Store.scalar(c, "SELECT COUNT(*) FROM auth_accounts")));
        long created = await(store.submit(c -> Store.scalar(c, "SELECT created FROM auth_accounts WHERE uuid=?", player.toString())));
        long events = await(store.submit(c -> Store.scalar(c, "SELECT COUNT(*) FROM audit WHERE action='auth.owner-bound'")));
        long auditedAt = await(store.submit(c -> Store.scalar(c, "SELECT created FROM audit WHERE action='auth.owner-bound'")));
        assertEquals(0, remaining); assertEquals(1, accounts); assertEquals(1, events);
        assertEquals(EXPIRES - 1, created); assertEquals(EXPIRES - 1, auditedAt);
    }
    @Test void restartDoesNotRevivePersistedExpiredProof() throws Exception {
        useClock(); seed(DIGEST, EXPIRES);
        store.close(); store = new Store(dir.resolve("bootstrap.db")); await(store.start());
        useClock(); await(repo.start()); clock.set(EXPIRES);
        denied(repo.register(player, OWNER, HASH, DIGEST, REQUESTED));
        unchanged(DIGEST, EXPIRES);
    }
    @Test void requestLowerBoundPreventsAClockStepBackWithinTheOperation() throws Exception {
        useClock(); seed(DIGEST, EXPIRES); clock.set(REQUESTED - 1);
        denied(repo.register(player, OWNER, HASH, DIGEST, EXPIRES));
        unchanged(DIGEST, EXPIRES);
    }
    @Test void invalidClockFailsClosedWithoutDurableEffects() throws Exception {
        useClock(); seed(DIGEST, EXPIRES);
        for (long invalid : new long[] {0, -1}) {
            clock.set(invalid);
            denied(repo.register(player, OWNER, HASH, DIGEST, REQUESTED));
            denied(repo.installOwnerChallenge(ORIGINAL, EXPIRES, REQUESTED));
            unchanged(DIGEST, EXPIRES);
        }
    }
    @Test void kdfAndStoreDelayCannotAuthenticateExpiredBootstrap() throws Exception {
        useClock(); String token = "A".repeat(43); String proof = Authentication.tokenDigest(token);
        seed(proof, EXPIRES);
        try (Authentication engine = new Authentication(repo)) {
            var connection = engine.open(player, OWNER, "loopback-synthetic");
            char[] input = "Only a synthetic bootstrap passphrase".toCharArray();
            CompletableFuture<Authentication.Result> registration;
            try (HeldStore held = new HeldStore()) {
                registration = engine.register(connection, input, token, REQUESTED);
                assertArrayEquals(new char[input.length], input);
                assertFalse(registration.isDone()); clock.set(EXPIRES);
            }
            assertEquals(Authentication.Result.DENIED, await(registration));
            assertFalse(engine.authenticated(connection));
        }
        unchanged(proof, EXPIRES);
    }
    @Test void provisioningKeepsTheOriginalMaximumTtlAndExecutionAuditTime() throws Exception {
        useClock(); seed(ORIGINAL, EXPIRES); clock.set(REQUESTED + 100);
        denied(repo.installOwnerChallenge(DIGEST, REQUESTED + 86_400_001L, REQUESTED));
        unchanged(ORIGINAL, EXPIRES);
        await(repo.installOwnerChallenge(DIGEST, REQUESTED + 86_400_000L, REQUESTED));
        assertEquals(DIGEST, await(store.submit(c -> Store.string(c, "SELECT challenge_hash FROM auth_owner WHERE id=1"))));
        long expires = await(store.submit(c -> Store.scalar(c, "SELECT expires FROM auth_owner WHERE id=1")));
        long auditedAt = await(store.submit(c -> Store.scalar(c, "SELECT created FROM audit WHERE action='auth.owner-challenge'")));
        assertEquals(REQUESTED + 86_400_000L, expires); assertEquals(clock.get(), auditedAt);
    }
}
