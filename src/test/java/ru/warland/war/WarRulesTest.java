package ru.warland.war;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.warland.war.WarRules.*;

class WarRulesTest {
    private long time(String iso) { return Instant.parse(iso).toEpochMilli(); }
    @Test void mobilizationIsExactlyTwelveHoursAndWindowsAreHalfOpen() {
        assertEquals(43_200_000L, WarRules.MOBILIZATION_MILLIS);
        Schedule window = new Schedule(19, 2);
        long starts = time("2026-06-01T16:00:00Z"), ends = starts + 3 * 86_400_000L;
        assertFalse(window.open(starts - 1, starts, ends));
        assertTrue(window.open(starts, starts, ends));
        assertTrue(window.open(starts + 7_200_000L - 1, starts, ends));
        assertFalse(window.open(starts + 7_200_000L, starts, ends));
        assertFalse(window.open(ends, starts, ends));
    }
    @Test void overnightWindowHasOneQuotaKeyAcrossMidnightAndMonthBoundary() {
        Schedule window = new Schedule(23, 2);
        long before = time("2026-06-30T20:59:59Z"), after = time("2026-06-30T21:00:00Z");
        assertEquals("2026-06-30", window.windowKey(before));
        assertEquals(window.windowKey(before), window.windowKey(after));
        assertTrue(window.open(after, before - 1000, after + 86_400_000));
        assertFalse(window.open(time("2026-06-30T22:00:00Z"), 0, Long.MAX_VALUE));
        assertEquals("23:00–01:00 МСК", window.label());
    }
    @Test void invalidWindowsFailClosed() {
        for (int[] invalid : List.of(new int[]{-1, 2}, new int[]{24, 2}, new int[]{19, 0}, new int[]{19, 9}))
            assertThrows(IllegalArgumentException.class, () -> new Schedule(invalid[0], invalid[1]));
    }
    @Test void resultUsesOverflowSafeFreshScoreDifference() {
        assertEquals("DRAW", WarRules.result(19, 0));
        assertEquals("ATTACKER_WIN", WarRules.result(20, 0));
        assertEquals("DEFENDER_WIN", WarRules.result(0, 20));
        assertEquals("ATTACKER_WIN", WarRules.result(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }
    @Test void connectivityProtectsCapitalAndRejectsDiagonalOrSeparatedIslands() {
        Chunk capital = new Chunk(-2, 0), edge = new Chunk(-1, 0), far = new Chunk(0, 0);
        assertTrue(WarRules.connected(Set.of(capital, edge, far), capital));
        assertFalse(WarRules.connected(Set.of(capital, far), capital));
        assertFalse(WarRules.connected(Set.of(capital, new Chunk(-1, 1)), capital));
        assertFalse(WarRules.connected(Set.of(edge), capital));
        assertFalse(WarRules.connected(Set.of(), capital));
    }
    @Test void oversizedTerritoryIsBounded() {
        Set<Chunk> claims = new HashSet<>();
        for (int i = 0; i < 1025; i++) claims.add(new Chunk(i, 0));
        assertFalse(WarRules.connected(claims, new Chunk(0, 0)));
    }
    @Test void typedKeysKeepDimensionNegativeCoordinatesAndWindowDistinct() {
        CaptureKey key = key("2026-06-01");
        assertEquals("minecraft:overworld", key.dimension());
        assertEquals(-17, key.x());
        assertNotEquals(key, key("2026-06-02"));
    }
    @Test void fullSpeedNeeds120SamplesAndOfflineNeeds480WithoutPlayerStacking() {
        CaptureProgress fast = new CaptureProgress(), slow = new CaptureProgress();
        for (int i = 1; i < 120; i++) assertFalse(fast.advance(key("day"), true, i * 1000L));
        assertTrue(fast.advance(key("day"), true, 120_000));
        for (int i = 1; i < 480; i++) assertFalse(slow.advance(key("day"), false, i * 1000L));
        assertTrue(slow.advance(key("day"), false, 480_000));
    }
    @Test void duplicateSampleDoesNotAccelerateAndLongPauseResets() {
        CaptureProgress progress = new CaptureProgress();
        for (int i = 0; i < 1000; i++) assertFalse(progress.advance(key("day"), true, 1000));
        for (int i = 2; i < 120; i++) assertFalse(progress.advance(key("day"), true, i * 1000L));
        assertFalse(progress.advance(key("day"), true, 126_000));
    }
    @Test void contestDepartureDisablePeaceAndWindowCloseDiscardProgress() {
        CaptureProgress progress = new CaptureProgress();
        for (int i = 1; i < 120; i++) progress.advance(key("day"), true, i * 1000L);
        progress.retain(Set.of());
        assertEquals(0, progress.size());
        assertFalse(progress.advance(key("day"), true, 120_000));
        assertFalse(progress.advance(key("next"), true, 121_000));
        progress.retain(Set.of(key("next")));
        assertEquals(1, progress.size());
        progress.remove(key("next"));
        assertEquals(0, progress.size());
    }
    @Test void defendersDisconnectingSlowButDoNotCancelProgress() {
        CaptureProgress progress = new CaptureProgress();
        for (int i = 1; i <= 100; i++) assertFalse(progress.advance(key("day"), true, i * 1000L));
        for (int i = 101; i < 180; i++) assertFalse(progress.advance(key("day"), false, i * 1000L));
        assertTrue(progress.advance(key("day"), false, 180_000));
    }
    private CaptureKey key(String window) { return new CaptureKey("war", "side", "minecraft:overworld", -17, 5, window); }
}
