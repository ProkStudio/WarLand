package ru.warland.season;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.season.SeasonRulesTest.*;

class SeasonStoreTest {
    static final class Fake {
        String initial;
        List<String> writes = new ArrayList<>();
        ArrayDeque<CompletableFuture<Void>> pending = new ArrayDeque<>();
        ArrayDeque<Runnable> server = new ArrayDeque<>();
        List<Throwable> failures = new ArrayList<>();
        SeasonStore store() {
            return new SeasonStore(() -> CompletableFuture.completedFuture(initial), json -> {
                writes.add(json);
                var result = new CompletableFuture<Void>(); pending.add(result); return result;
            }, server::add, failures::add);
        }
        void flush() { while (!server.isEmpty()) server.remove().run(); }
        SeasonStore loaded() { var s = store(); s.load(() -> {}); flush(); return s; }
        void ack() { pending.remove().complete(null); flush(); }
    }
    @Test void loadedCallbackIsServerDispatchedWithoutRewritingExistingData() {
        var f = new Fake(); f.initial = SeasonStore.JSON.toJson(scheduled());
        var s = f.store(); var loaded = new AtomicInteger(); s.load(loaded::incrementAndGet);
        assertFalse(s.ready()); assertEquals(0, loaded.get()); f.flush();
        assertTrue(s.ready()); assertEquals(1, loaded.get()); assertTrue(f.writes.isEmpty());
        assertEquals(scheduled(), s.snapshot());
    }
    @Test void nothingPublishesBeforeAckAndBusyWriterRejectsWithoutQueueing() {
        var f = new Fake(); var s = f.loaded(); var calls = new AtomicInteger(); var rejected = new AtomicInteger();
        s.change(old -> scheduled(), c -> calls.incrementAndGet(), e -> fail(e));
        s.change(old -> scheduled(), c -> fail("Busy writer must not queue"), e -> rejected.incrementAndGet());
        assertEquals(1, f.writes.size()); assertEquals(1, rejected.get());
        assertEquals(0, s.snapshot().revision()); assertEquals(0, calls.get());
        f.pending.remove().complete(null); assertEquals(0, calls.get()); f.flush();
        assertEquals(1, calls.get()); assertEquals(scheduled(), s.snapshot()); assertTrue(s.idle());
    }
    @Test void failedWriteBlocksFurtherEditsAndNeverPublishesSuccess() {
        var f = new Fake(); var s = f.loaded(); var rejected = new AtomicInteger();
        s.change(old -> scheduled(), c -> fail("No acknowledgement"), e -> rejected.incrementAndGet());
        f.pending.remove().completeExceptionally(new IllegalStateException("Injected I/O failure")); f.flush();
        assertFalse(s.ready()); assertEquals(1, rejected.get()); assertEquals(1, f.failures.size());
        s.change(old -> scheduled(), c -> fail("Locked"), e -> rejected.incrementAndGet());
        assertEquals(2, rejected.get()); assertEquals(1, f.writes.size());
    }
    @Test void durableReloadAfterLostCallbackMakesRetryIdempotent() {
        var f = new Fake(); var s = f.loaded();
        s.change(old -> scheduled(), c -> fail("Closed"), e -> fail(e));
        String persisted = f.writes.getFirst(); s.close(); f.ack();
        var restarted = new Fake(); restarted.initial = persisted; var fresh = restarted.loaded();
        fresh.change(old -> SeasonRules.schedule(old, "season-one", START, "console", REASON, NOW),
                c -> assertFalse(c.changed()), e -> fail(e));
        assertTrue(restarted.writes.isEmpty()); assertEquals(1, fresh.snapshot().seasons().size());
    }
    @Test void corruptFutureIncompleteOrOversizedDocumentsAreNeverOverwritten() {
        for (String invalid : List.of("", "null", "{}", "{broken", "{\"schema\":999}", "x".repeat(SeasonStore.MAX_JSON_CHARS + 1),
                SeasonStore.JSON.toJson(scheduled()).replace("\"SCHEDULED\"", "\"ARCHIVED\""))) {
            var f = new Fake(); f.initial = invalid; var s = f.loaded();
            assertFalse(s.ready(), invalid.substring(0, Math.min(20, invalid.length())));
            assertTrue(f.writes.isEmpty()); assertEquals(1, f.failures.size());
        }
    }
    @Test void invalidEditDoesNotPoisonGoodStateOrWrite() {
        var f = new Fake(); var s = f.loaded(); var rejected = new AtomicInteger();
        s.change(old -> SeasonRules.schedule(old, "bad", 0, "console", REASON, NOW), c -> fail("Invalid"), e -> rejected.incrementAndGet());
        assertEquals(1, rejected.get()); assertTrue(s.ready()); assertTrue(f.writes.isEmpty());
        s.change(old -> scheduled(), c -> {}, e -> fail(e)); f.ack(); assertEquals(1, s.snapshot().revision());
    }
    @Test void idleLifecycleDoesNotWriteAndSnapshotRoundTrips() {
        var f = new Fake(); f.initial = SeasonStore.JSON.toJson(scheduled()); var s = f.loaded();
        s.change(old -> SeasonRules.advance(old, NOW), c -> assertFalse(c.changed()), e -> fail(e));
        assertTrue(f.writes.isEmpty());
        s.change(old -> SeasonRules.advance(old, at(SeasonState.endFor(START) + SeasonState.DAY)), c -> {}, e -> fail(e)); f.ack();
        assertEquals(s.snapshot(), SeasonStore.decode(f.writes.getLast()));
        assertEquals(SeasonState.Phase.ARCHIVED, s.snapshot().seasons().getFirst().phase());
    }
    @Test void shutdownSuppressesLoadAndWriteCallbacks() {
        var f = new Fake(); var s = f.store(); s.load(() -> fail("Closed load")); s.close(); f.flush(); assertFalse(s.ready());
        var g = new Fake(); var other = g.loaded(); other.change(old -> scheduled(), c -> fail("Closed write"), e -> fail(e));
        other.close(); g.ack(); assertFalse(other.ready());
    }
    @Test void notificationFailureCannotUndoCommittedState() {
        var f = new Fake(); var s = f.loaded();
        s.change(old -> scheduled(), c -> { throw new IllegalStateException("Notification failed"); }, e -> fail(e)); f.ack();
        assertTrue(s.ready()); assertEquals(scheduled(), s.snapshot()); assertEquals(1, f.failures.size());
    }
}
