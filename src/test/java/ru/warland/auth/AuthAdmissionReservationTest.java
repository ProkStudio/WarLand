package ru.warland.auth;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AuthAdmissionReservationTest {
    @TempDir Path dir;
    Store store; AuthRepository repo; Authentication auth;
    UUID player=UUID.randomUUID();
    static Passwords.Hash hash;
    static char[] password() { return "isolated reservation test phrase".toCharArray(); }
    @BeforeAll static void hashFixture() { hash=Passwords.hash(password()); }
    @BeforeEach void start() throws Exception {
        store=new Store(dir.resolve("auth.db")); store.start().get();
        repo=new AuthRepository(store); repo.start().get(); auth=new Authentication(repo);
        repo.register(player,"TestUser",hash,null,System.currentTimeMillis()).get();
    }
    @AfterEach void stop() { if(auth!=null) auth.close(); if(store!=null) store.close(); }

    @Test void delayedConfigurationReservationPreservesAuthenticatedAccount() throws Exception {
        var incumbent=auth.reserve(player,"TestUser","peer-one");
        assertEquals(Authentication.Result.SUCCESS,auth.login(incumbent,password()).get(10,TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> auth.reserve(player,"TESTUSER","attacker-peer"));
        assertTrue(auth.authenticated(incumbent)); assertFalse(auth.owner(incumbent).get());
        assertEquals(hash,repo.account(player,"TestUser").get().password());
        assertTrue(repo.current(player,1).get()); assertNull(repo.owner().get());
        assertEquals("1",store.submit(c -> Store.string(c,"SELECT count(*) FROM auth_accounts")).get());
    }
    @Test void duplicateDoesNotCancelIncumbentLoginQueuedBehindDatabaseWork() throws Exception {
        var incumbent=auth.reserve(player,"TestUser","peer-one");
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        var block=store.submit(c -> { entered.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); return null; });
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS)); char[] input=password();
            var pending=auth.login(incumbent,input); assertArrayEquals(new char[input.length],input);
            assertThrows(IllegalStateException.class, () -> auth.reserve(player,"TestUser","attacker-peer"));
            assertFalse(pending.isDone(),"duplicate must not cancel or publish incumbent work");
            release.countDown(); block.get(5,TimeUnit.SECONDS);
            assertEquals(Authentication.Result.SUCCESS,pending.get(10,TimeUnit.SECONDS));
            assertTrue(auth.authenticated(incumbent));
        } finally { release.countDown(); }
    }
    @Test void deniedDuplicateCannotPreventDisconnectThenRealReconnect() throws Exception {
        var old=auth.reserve(player,"TestUser","peer-one");
        assertThrows(IllegalStateException.class, () -> auth.reserve(player,"TestUser","peer-two"));
        auth.disconnect(old); var next=auth.reserve(player,"TestUser","peer-two");
        auth.disconnect(old);
        assertEquals(Authentication.Result.SUCCESS,auth.login(next,password()).get(10,TimeUnit.SECONDS));
        assertTrue(auth.authenticated(next)); assertFalse(auth.authenticated(old));
    }
    @Test void wrongPasswordStillDeniedThenCorrectPasswordAcceptedOnSameReservation() throws Exception {
        var c=auth.reserve(player,"TestUser","peer-one");
        assertEquals(Authentication.Result.DENIED,auth.login(c,"wrong synthetic passphrase".toCharArray()).get(10,TimeUnit.SECONDS));
        assertFalse(auth.authenticated(c));
        assertThrows(IllegalStateException.class, () -> auth.reserve(player,"TestUser","peer-two"));
        assertEquals(Authentication.Result.SUCCESS,auth.login(c,password()).get(10,TimeUnit.SECONDS));
        assertTrue(auth.authenticated(c));
    }
    @Test void invalidMetadataAndClosedEngineFailWithoutReplacingIncumbent() throws Exception {
        var c=auth.reserve(player,"TestUser","peer-one");
        assertThrows(IllegalArgumentException.class, () -> auth.reserve(player,"../bad","peer-two"));
        assertThrows(IllegalStateException.class, () -> auth.reserve(player,"TestUser",""));
        assertEquals(Authentication.Result.SUCCESS,auth.login(c,password()).get(10,TimeUnit.SECONDS));
        auth.close(); assertThrows(IllegalStateException.class, () -> auth.reserve(player,"TestUser","peer-two"));
        assertFalse(auth.authenticated(c));
    }
    @Test void runtimeAdmissionUsesExclusiveEngineEntryNotGenerationReplacement() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/warland/auth/AuthRuntime.java"));
        assertTrue(source.contains("engine.reserve(profile.id(), profile.name(), address.getAddress().getHostAddress())"));
        assertFalse(source.contains("engine.open("));
        assertTrue(source.contains("catch (RuntimeException unavailable) { disconnect(handler); handler.disconnect(DENIED); }"));
    }
}
