package ru.warland.season;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.season.SeasonState.*;

class SeasonRulesTest {
    static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    static final long START = Instant.parse("2026-08-10T21:00:00Z").toEpochMilli();
    static final String REASON = "Проверенный календарь сезона";
    static SeasonState scheduled() { return SeasonRules.schedule(SeasonState.empty(), "season-one", START, "console", REASON, NOW); }
    static Instant at(long time) { return Instant.ofEpochMilli(time); }

    @Test void twoCalendarMonthsUseMoscowRatherThanSixtyDays() {
        long january = Instant.parse("2027-01-30T21:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2027-03-30T21:00:00Z").toEpochMilli(), endFor(january));
        long december = Instant.parse("2027-12-30T21:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2028-02-28T21:00:00Z").toEpochMilli(), endFor(december));
    }
    @Test void everyDeadlineIsInclusiveAndTheTickBeforeItDoesNothing() {
        SeasonState s = scheduled();
        for (Phase phase : List.of(Phase.ACTIVE, Phase.FINAL_WEEK, Phase.FINALIZING, Phase.ARCHIVED)) {
            long deadline = due(phase, START, endFor(START));
            assertSame(s, SeasonRules.advance(s, at(deadline - 1)));
            long revision = s.revision();
            s = SeasonRules.advance(s, at(deadline));
            assertEquals(phase, s.seasons().getFirst().phase());
            assertEquals(revision + 1, s.revision());
            assertSame(s, SeasonRules.advance(s, at(deadline)));
        }
        assertNull(s.current());
        assertEquals(5, s.seasons().getFirst().history().size());
    }
    @Test void downtimeCatchUpRecordsAllPhasesInOneRevision() {
        SeasonState s = SeasonRules.advance(scheduled(), at(endFor(START) + 2 * DAY));
        assertEquals(2, s.revision());
        assertEquals(Phase.ARCHIVED, s.seasons().getFirst().phase());
        assertEquals(5, s.seasons().getFirst().history().size());
        assertSame(s, SeasonRules.advance(s, at(START)));
    }
    @Test void rollbackNeverReopensASeasonOrReemitsFinalWeek() {
        SeasonState s = SeasonRules.advance(scheduled(), at(endFor(START) - 7 * DAY));
        assertEquals(Phase.FINAL_WEEK, s.current().phase());
        assertSame(s, SeasonRules.advance(s, at(START - DAY)));
    }
    @Test void scheduleRetryIsIdempotentButDifferentParametersConflict() {
        SeasonState s = scheduled();
        assertSame(s, SeasonRules.schedule(s, "season-one", START, "console", REASON, NOW));
        assertThrows(IllegalArgumentException.class, () -> SeasonRules.schedule(s, "season-one", START + DAY, "console", REASON, NOW));
        assertThrows(IllegalArgumentException.class, () -> SeasonRules.schedule(s, "season-two", START + DAY, "console", REASON, NOW));
    }
    @Test void cancelOnlyBeforeStartEvenWhenTickHasNotCaughtUp() {
        SeasonState s = scheduled();
        assertThrows(IllegalArgumentException.class, () -> SeasonRules.cancel(s, "season-one", "console", REASON, at(START)));
        SeasonState cancelled = SeasonRules.cancel(s, "season-one", "console", REASON, at(START - 1));
        assertEquals(Phase.CANCELLED, cancelled.seasons().getFirst().phase());
        assertSame(cancelled, SeasonRules.advance(cancelled, at(endFor(START) + DAY)));
        assertSame(cancelled, SeasonRules.cancel(cancelled, "season-one", "console", REASON, at(START)));
        assertSame(cancelled, SeasonRules.schedule(cancelled, "season-one", START, "console", REASON, at(START)));
    }
    @Test void noImplicitSeasonAndNoPerSecondIdleWrites() {
        SeasonState empty = SeasonState.empty();
        assertSame(empty, SeasonRules.advance(empty, NOW));
        SeasonState scheduled = scheduled();
        assertSame(scheduled, SeasonRules.advance(scheduled, NOW.plusSeconds(60)));
    }
    @Test void rejectsInvalidScheduleAndReasons() {
        for (long bad : new long[]{0, NOW.toEpochMilli(), NOW.toEpochMilli() + 299_999, NOW.toEpochMilli() + 367 * DAY})
            assertThrows(IllegalArgumentException.class, () -> SeasonRules.schedule(SeasonState.empty(), "valid-id", bad, "console", REASON, NOW));
        for (String id : List.of("a", "with space", "§4admin", "../path"))
            assertThrows(IllegalArgumentException.class, () -> SeasonRules.schedule(SeasonState.empty(), id, START, "console", REASON, NOW));
        for (String reason : List.of("short", "line\nbreak", "§4injected text", "x".repeat(161)))
            assertThrows(IllegalArgumentException.class, () -> SeasonRules.schedule(SeasonState.empty(), "valid-id", START, "console", reason, NOW));
    }
    @Test void archiveCapacityFailsWithoutDeletingAnything() {
        SeasonState state = SeasonState.empty();
        for (int i = 0; i < MAX_SEASONS; i++) {
            state = SeasonRules.schedule(state, "season-" + i, START, "console", REASON, NOW);
            state = SeasonRules.cancel(state, "season-" + i, "console", REASON, NOW);
        }
        SeasonState full = state;
        assertThrows(IllegalArgumentException.class, () -> SeasonRules.schedule(full, "too-many", START, "console", REASON, NOW));
        assertEquals(MAX_SEASONS, full.seasons().size());
    }
    @Test void documentCannotMutateOrSkipPhaseHistory() {
        var s = scheduled();
        assertThrows(UnsupportedOperationException.class, () -> s.seasons().clear());
        assertThrows(UnsupportedOperationException.class, () -> s.current().history().clear());
        var history = new ArrayList<>(s.current().history());
        history.add(new Transition(Phase.ARCHIVED, endFor(START) + DAY, endFor(START) + DAY, "server", REASON));
        assertThrows(IllegalArgumentException.class, () -> new Season("skip-phase", START, endFor(START), history));
        assertThrows(IllegalArgumentException.class, () -> new SeasonState(999, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SeasonState(1, 0, 0, s.seasons()));
        assertThrows(IllegalArgumentException.class, () -> new SeasonState(1, 0, s.highWatermark(), List.of(s.current(), s.current())));
    }
    @Test void countersFailClosedAtOverflow() {
        SeasonState max = new SeasonState(1, Long.MAX_VALUE, 0, List.of());
        assertThrows(ArithmeticException.class, () -> SeasonRules.schedule(max, "valid-id", START, "console", REASON, NOW));
    }
}
