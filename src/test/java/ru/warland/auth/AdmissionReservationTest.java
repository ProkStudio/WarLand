package ru.warland.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AdmissionReservationTest {
    @Test void pendingDuplicateIsRejectedWithoutChangingNonce() {
        Admission a = new Admission(); UUID p = UUID.randomUUID(), n = a.reserve(p);
        assertThrows(IllegalStateException.class, () -> a.reserve(p));
        assertTrue(a.pending(p, n)); assertTrue(a.authenticate(p, n));
    }
    @Test void delayedDuplicateCannotRevokeAuthenticatedIncumbent() {
        Admission a = new Admission(); UUID p = UUID.randomUUID(), n = a.reserve(p);
        assertTrue(a.authenticate(p, n));
        for (int i = 0; i < 50; i++) assertThrows(IllegalStateException.class, () -> a.reserve(p));
        assertTrue(a.authenticated(p, n));
    }
    @Test void duplicateDoesNotExtendPendingDeadline() {
        AtomicLong clock = new AtomicLong(); Admission a = new Admission(clock::get);
        UUID p = UUID.randomUUID(), old = a.reserve(p);
        clock.set(TimeUnit.SECONDS.toNanos(119));
        assertThrows(IllegalStateException.class, () -> a.reserve(p));
        clock.set(TimeUnit.SECONDS.toNanos(120)); UUID next = a.reserve(p);
        assertNotEquals(old, next); assertFalse(a.authenticate(p, old)); assertTrue(a.pending(p, next));
    }
    @Test void authenticatedReservationLivesUntilExactSessionDeadline() {
        AtomicLong clock = new AtomicLong(); Admission a = new Admission(clock::get);
        UUID p = UUID.randomUUID(), old = a.reserve(p); assertTrue(a.authenticate(p, old));
        clock.set(TimeUnit.HOURS.toNanos(8) - 1);
        assertThrows(IllegalStateException.class, () -> a.reserve(p)); assertTrue(a.authenticated(p, old));
        clock.incrementAndGet(); UUID next = a.reserve(p);
        assertFalse(a.authenticated(p, old)); assertTrue(a.pending(p, next));
    }
    @Test void staleDisconnectCannotCloseReconnectedReservation() {
        Admission a = new Admission(); UUID p = UUID.randomUUID(), old = a.reserve(p);
        a.close(p, old); UUID next = a.reserve(p); a.close(p, old);
        assertTrue(a.pending(p, next)); assertTrue(a.authenticate(p, next));
        a.close(p, UUID.randomUUID()); assertTrue(a.authenticated(p, next));
    }
    @Test void differentPlayersAndCapacityRemainIsolated() {
        Admission a = new Admission(); Map<UUID, UUID> all = new HashMap<>();
        for (int i=0; i<128; i++) { UUID p=UUID.randomUUID(); all.put(p,a.reserve(p)); }
        UUID incumbent = all.keySet().iterator().next();
        assertThrows(IllegalStateException.class, () -> a.reserve(incumbent));
        assertThrows(IllegalStateException.class, () -> a.reserve(UUID.randomUUID()));
        all.forEach((p,n) -> assertTrue(a.pending(p,n)));
        a.close(incumbent, all.get(incumbent)); assertNotNull(a.reserve(UUID.randomUUID()));
    }
    @Test void simultaneousReservationsHaveExactlyOneWinner() throws Exception {
        Admission a = new Admission(); UUID p = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16), release = new CountDownLatch(1);
        List<Future<UUID>> outcomes = new ArrayList<>();
        try {
            for (int i=0; i<16; i++) outcomes.add(pool.submit(() -> {
                ready.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
                try { return a.reserve(p); } catch (IllegalStateException occupied) { return null; }
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS)); release.countDown();
            List<UUID> winners = new ArrayList<>();
            for (Future<UUID> result : outcomes) { UUID n=result.get(5,TimeUnit.SECONDS); if(n!=null) winners.add(n); }
            assertEquals(1,winners.size()); assertTrue(a.pending(p,winners.getFirst()));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test void expiredEntryCleanupDoesNotDisplaceAnotherLiveReservation() {
        AtomicLong clock = new AtomicLong(); Admission a = new Admission(clock::get);
        UUID expired=UUID.randomUUID(), live=UUID.randomUUID(); a.reserve(expired);
        clock.set(TimeUnit.SECONDS.toNanos(100)); UUID n=a.reserve(live);
        clock.set(TimeUnit.SECONDS.toNanos(120)); assertNotNull(a.reserve(expired));
        assertTrue(a.pending(live,n)); assertThrows(IllegalStateException.class, () -> a.reserve(live));
    }
}
