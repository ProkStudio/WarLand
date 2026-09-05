package ru.warland.core;

import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RulesBoundaryTest {
    @Test void namesAcceptRussianAndExactLengths() {
        assertTrue(Rules.validNation("Север-01"));
        assertTrue(Rules.validNation("a".repeat(3)));
        assertTrue(Rules.validNation("Я".repeat(20)));
        assertFalse(Rules.validNation("a".repeat(21)));
    }

    @Test void namesRejectNullControlCharactersAndMarkup() {
        for (String name : new String[]{null, "", "ab", "two words", "a\nb", "a'b", "<abc>", "a/b", "a§b"})
            assertFalse(Rules.validNation(name), () -> "Accepted invalid name: " + name);
    }

    @Test void claimLimitFloorsNegativeInputs() {
        assertEquals(9, Rules.claimLimit(-1, -1));
        assertEquals(9, Rules.claimLimit(Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertEquals(18, Rules.claimLimit(0, 1));
    }

    @Test void claimLimitSaturatesWithoutIntegerOverflow() {
        for (int[] input : new int[][]{{Integer.MAX_VALUE, 0}, {0, Integer.MAX_VALUE},
                {Integer.MAX_VALUE, Integer.MAX_VALUE}, {715827883, 0}, {0, 238609295}})
            assertEquals(256, Rules.claimLimit(input[0], input[1]));
    }

    @Test void claimLimitRemainsMonotonicAtCap() {
        int previous = 9;
        for (int active = 0; active <= 1000; active++) {
            int limit = Rules.claimLimit(active, 0);
            assertTrue(limit >= previous && limit <= 256);
            previous = limit;
        }
        assertEquals(255, Rules.claimLimit(82, 0));
        assertEquals(256, Rules.claimLimit(83, 0));
    }

    @Test void totalEnforcesInclusiveQuantityBounds() {
        assertEquals(2304, Rules.total(1, 2304));
        assertEquals(Long.MAX_VALUE, Rules.total(Long.MAX_VALUE, 1));
        for (int quantity : new int[]{Integer.MIN_VALUE, -1, 0, 2305, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> Rules.total(1, quantity));
        for (long price : new long[]{Long.MIN_VALUE, -1, 0})
            assertThrows(IllegalArgumentException.class, () -> Rules.total(price, 1));
    }

    @Test void totalRejectsMultiplicationOverflow() {
        assertThrows(ArithmeticException.class, () -> Rules.total(Long.MAX_VALUE, 2));
        assertThrows(ArithmeticException.class, () -> Rules.total(Long.MAX_VALUE / 2304 + 1, 2304));
    }

    @Test void priceHandlesSignedExtremesAndLargeBaseWithoutOverflow() {
        for (long base : new long[]{1, 99, 100, 101, 100000, Long.MAX_VALUE}) {
            for (int sold : new int[]{Integer.MIN_VALUE, 0, 1, 500, 1000, Integer.MAX_VALUE}) {
                long percent = Math.max(25, 100 - 75L * Math.max(0, sold) / 1000);
                long expected = BigInteger.valueOf(base).multiply(BigInteger.valueOf(percent))
                        .divide(BigInteger.valueOf(100)).max(BigInteger.ONE).longValueExact();
                assertEquals(expected, Rules.price(base, sold, 1000), "base=" + base + ", sold=" + sold);
            }
        }
    }

    @Test void priceRejectsInvalidBaseAndQuota() {
        for (long base : new long[]{Long.MIN_VALUE, -1, 0})
            assertThrows(IllegalArgumentException.class, () -> Rules.price(base, 0, 1));
        for (int quota : new int[]{Integer.MIN_VALUE, -1, 0})
            assertThrows(IllegalArgumentException.class, () -> Rules.price(1, 0, quota));
    }

    @Test void priceIsMonotonicAndClamped() {
        long previous = 100;
        for (int sold = 0; sold <= 1500; sold++) {
            long price = Rules.price(100, sold, 1000);
            assertTrue(price <= previous && price >= 25);
            previous = price;
        }
        assertEquals(100, Rules.price(100, -1, 1000));
        assertEquals(25, Rules.price(100, Integer.MAX_VALUE, 1));
    }

    @Test void combatWindowIncludesStartExcludesEndAndCrossesMidnight() {
        long start = Instant.parse("2026-09-05T20:00:00Z").toEpochMilli(); // 23:00 Moscow
        long end = start + 24 * 3600000L;
        assertFalse(Rules.combatWindow(start - 1, start, end, 23, 2));
        assertTrue(Rules.combatWindow(start, start, end, 23, 2));
        assertTrue(Rules.combatWindow(start + 2 * 3600000L - 1, start, end, 23, 2));
        assertFalse(Rules.combatWindow(start + 2 * 3600000L, start, end, 23, 2));
        assertFalse(Rules.combatWindow(end, start, end, 23, 2));
    }

    @Test void moscowCalendarChangesExactlyAtLocalMidnight() {
        long midnight = Instant.parse("2026-09-05T21:00:00Z").toEpochMilli();
        assertEquals("2026-09-05", Rules.day(midnight - 1));
        assertEquals("2026-09-06", Rules.day(midnight));
        assertEquals("2026-01-01", Rules.day(Instant.parse("2025-12-31T21:00:00Z").toEpochMilli()));
    }
}
