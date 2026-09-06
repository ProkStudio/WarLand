package ru.warland.rtp;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.rtp.RtpPolicy.Rejection.*;

class RtpPolicyTest {
    static final int X = 1608, Y = 70, Z = 8;
    static final RtpPolicy.Cell AIR = new RtpPolicy.Cell(true, true, false, false, false, false);
    static final RtpPolicy.Cell ROCK = new RtpPolicy.Cell(false, false, true, false, false, false);
    static final RtpPolicy.Cell WATER = new RtpPolicy.Cell(false, true, false, true, false, false);
    static final RtpPolicy.Cell HAZARD_CELL = new RtpPolicy.Cell(false, false, true, false, true, false);
    record P(int x, int y, int z) {}
    record Column(int x, int z) {}
    static class Site implements RtpPolicy.Terrain {
        String dimension = RtpPolicy.WORLD;
        boolean loaded = true, collision = true;
        int bottom = -64, top = 319, surface = Y, cellReads;
        Map<P, RtpPolicy.Cell> cells = new HashMap<>();
        Set<Column> outside = new HashSet<>(), protectedColumns = new HashSet<>();
        public String dimension() { return dimension; }
        public boolean loaded() { return loaded; }
        public int bottomY() { return bottom; }
        public int topY() { return top; }
        public int surfaceY(int x, int z) { return surface; }
        public boolean insideBorder(int x, int z) { return !outside.contains(new Column(x, z)); }
        public boolean protectedColumn(int x, int z) { return !protectedColumns.contains(new Column(x, z)) ? false : true; }
        public RtpPolicy.Cell cell(int x, int y, int z) {
            cellReads++;
            assertEquals(X >> 4, x >> 4, "Safety checks must not spill into another chunk");
            assertEquals(Z >> 4, z >> 4, "Safety checks must not spill into another chunk");
            return cells.getOrDefault(new P(x, y, z), y < Y ? ROCK : AIR);
        }
        public boolean collisionFree(int x, int y, int z) { return collision; }
        void set(int dx, int dy, int dz, RtpPolicy.Cell cell) { cells.put(new P(X + dx, Y + dy, Z + dz), cell); }
        RtpPolicy.Rejection assess() { return RtpPolicy.evaluate(this, 0, 0, X, Y, Z); }
    }

    @Test void drySurfaceHasBoundedFootprint() {
        Site site = new Site(); assertEquals(NONE, site.assess()); assertEquals(100, site.cellReads);
    }
    @Test void exactAnnulusAndNonzeroSpawn() {
        assertTrue(RtpPolicy.inAnnulus(0, 0, 1000, 0)); assertTrue(RtpPolicy.inAnnulus(0, 0, 3000, 0));
        assertFalse(RtpPolicy.inAnnulus(0, 0, 999, 0)); assertFalse(RtpPolicy.inAnnulus(0, 0, 3001, 0));
        assertTrue(RtpPolicy.inAnnulus(100, -70, -900, -70));
        assertTrue(RtpPolicy.inAnnulus(0, 0, 1800, 2400));
        assertFalse(RtpPolicy.inAnnulus(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
    }
    @Test void seededSamplingIsAreaBasedNotSquareOrOriginBased() {
        Random random = new Random(4391);
        long inner = 0, outer = 0;
        for (int i = 0; i < 10000; i++) {
            var c = RtpPolicy.sample(random, 345, -892);
            double distance = Math.hypot(c.x() - 345, c.z() + 892);
            assertTrue(distance > 998 && distance < 3002); // Integer rounding is separately fenced.
            if (distance < Math.sqrt(5_000_000)) inner++; else outer++;
        }
        assertTrue(inner > 4500 && outer > 4500, "Area halves should both be sampled");
    }
    @Test void dimensionRangeAndUnloadedFailBeforeBlockReads() {
        Site site = new Site(); site.dimension = "warland:capital"; assertEquals(WORLD, site.assess());
        site.dimension = "minecraft:the_nether"; assertEquals(WORLD, site.assess());
        site.dimension = "minecraft:the_end"; assertEquals(WORLD, site.assess());
        site.dimension = RtpPolicy.WORLD; site.loaded = false; assertEquals(UNLOADED, site.assess());
        assertEquals(RANGE, RtpPolicy.evaluate(site, X, Z, X, Y, Z)); assertEquals(0, site.cellReads);
    }
    @Test void interiorMarginWorksAcrossNegativeChunks() {
        for (int base : new int[]{-320, -16, 0, 16, 1600}) for (int offset = 0; offset < 16; offset++) {
            assertEquals(offset >= 3 && offset <= 12, RtpPolicy.interior(base + offset, 8));
            assertEquals(offset >= 3 && offset <= 12, RtpPolicy.interior(8, base + offset));
        }
        Site site = new Site(); assertEquals(CHUNK_EDGE, RtpPolicy.evaluate(site, 0, 0, 1600, Y, Z));
    }
    @Test void buildLimitsAndCaveOrRoofFail() {
        Site site = new Site(); site.bottom = Y; assertEquals(HEIGHT, site.assess());
        site.bottom = -64; site.top = Y + 1; assertEquals(HEIGHT, site.assess());
        site.top = Y + 2; assertEquals(NONE, site.assess());
        site.surface = Y + 5; assertEquals(NOT_SURFACE, site.assess());
        site.surface = Y - 1; assertEquals(NOT_SURFACE, site.assess());
    }
    @Test void feetHeadAndStandingHeadroomMustReallyBeAir() {
        for (int dy = 0; dy <= 2; dy++) {
            Site site = new Site(); site.set(0, dy, 0, ROCK); assertEquals(HEADROOM, site.assess());
        }
        Site site = new Site(); site.set(0, 1, 0, new RtpPolicy.Cell(true, false, false, false, false, false));
        assertEquals(HEADROOM, site.assess(), "A claimed air flag cannot override its collision shape");
    }
    @Test void fullDryStableFloorIsRequiredForTheWholePad() {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Site site = new Site(); site.set(dx, -1, dz, AIR); assertEquals(FLOOR, site.assess());
            site.set(dx, -1, dz, new RtpPolicy.Cell(false, false, false, false, false, false));
            assertEquals(FLOOR, site.assess(), "Slabs and partial collision are rejected");
            site.set(dx, -1, dz, new RtpPolicy.Cell(false, false, true, false, false, true));
            assertEquals(FLOOR, site.assess(), "Leaves and falling floors are rejected");
        }
    }
    @Test void fluidInFloorFeetHeadOrOuterNeighborsFails() {
        for (int dy = -1; dy <= 2; dy++) {
            Site site = new Site(); site.set(0, dy, 0, WATER); assertEquals(HAZARD, site.assess());
            site = new Site(); site.set(2, dy, -2, WATER); assertEquals(HAZARD, site.assess());
        }
        Site site = new Site(); site.set(0, -1, 0, new RtpPolicy.Cell(false, false, true, true, false, false));
        assertEquals(HAZARD, site.assess(), "Waterlogged full floor is not dry");
    }
    @Test void hazardNeighborsIncludeTheEntireRing() {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Site site = new Site(); site.set(dx, -1, dz, HAZARD_CELL); assertEquals(HAZARD, site.assess());
        }
    }
    @Test void dangerousAndUnknownBlocksFailClosed() {
        for (String id : new String[]{"magma_block", "cactus", "powder_snow", "fire", "soul_fire", "campfire",
                "soul_campfire", "sweet_berry_bush", "wither_rose", "pointed_dripstone", "lava", "water",
                "ice", "blue_ice", "cobweb", "oak_pressure_plate", "light_weighted_pressure_plate", "nether_portal"})
            assertTrue(RtpPolicy.hazardous("minecraft:" + id), id);
        assertTrue(RtpPolicy.hazardous(null)); assertTrue(RtpPolicy.hazardous("unknown:floor"));
        assertFalse(RtpPolicy.hazardous("minecraft:grass_block")); assertFalse(RtpPolicy.hazardous("minecraft:stone"));
    }
    @Test void borderOrClaimInAnyNeighborRejectsEvenForSafeCenter() {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Site site = new Site(); site.outside.add(new Column(X + dx, Z + dz)); assertEquals(BORDER, site.assess());
            site = new Site(); site.protectedColumns.add(new Column(X + dx, Z + dz)); assertEquals(PROTECTED, site.assess());
        }
    }
    @Test void finalCollisionAndLateDestinationChangesAreRechecked() {
        Site site = new Site(); assertEquals(NONE, site.assess());
        site.collision = false; assertEquals(COLLISION, site.assess());
        site.collision = true; site.protectedColumns.add(new Column(X, Z)); assertEquals(PROTECTED, site.assess());
        site.protectedColumns.clear(); site.set(0, -1, 0, WATER); assertEquals(HAZARD, site.assess());
    }
    @Test void borderCheckMeansFullContainmentNotIntersection() {
        assertTrue(RtpPolicy.containsBox(0, 0, 10, 10, 0, 0, 10, 10));
        assertFalse(RtpPolicy.containsBox(0, 0, 10, 10, -0.1, 3, 3, 4));
        assertFalse(RtpPolicy.containsBox(0, 0, 10, 10, 9, 3, 10.1, 4));
        assertFalse(RtpPolicy.containsBox(0, 0, 10, 10, 3, -1, 4, 3));
        assertFalse(RtpPolicy.containsBox(0, 0, 10, 10, 3, 9, 4, 11));
        assertFalse(RtpPolicy.containsBox(0, 0, 10, 10, Double.NaN, 3, 4, 4));
        assertFalse(RtpPolicy.containsBox(0, 0, 10, 10, 3, 3, 3, 4));
    }
    @Test void movementIncludesFractionalHorizontalVerticalAndInvalidValues() {
        assertFalse(RtpPolicy.moved(0, 70, 0, 0.01, 70, 0));
        assertTrue(RtpPolicy.moved(0, 70, 0, 0.06, 70, 0));
        assertTrue(RtpPolicy.moved(0, 70, 0, 0, 70.06, 0));
        assertTrue(RtpPolicy.moved(0, 70, 0, 0, 70, -0.06));
        assertTrue(RtpPolicy.moved(0, 70, 0, Double.NaN, 70, 0));
    }
    @Test void budgetsHaveFiniteBoundariesIncludingNanoTimeWrap() {
        assertTrue(RtpPolicy.hasBudget(31, 7, 31)); assertFalse(RtpPolicy.hasBudget(32, 7, 31));
        assertFalse(RtpPolicy.hasBudget(31, 8, 31)); assertFalse(RtpPolicy.hasBudget(31, 7, 32));
        assertFalse(RtpPolicy.expired(100, 109, 10)); assertTrue(RtpPolicy.expired(100, 110, 10));
        assertTrue(RtpPolicy.expired(Long.MAX_VALUE - 5, Long.MIN_VALUE + 5, 10));
        assertTrue(RtpPolicy.LOAD_NANOS < RtpPolicy.SEARCH_NANOS);
    }
    @Test void cooldownIs180SecondsCeiledAndReconnectIndependent() {
        long until = RtpPolicy.deadline(1_000_000);
        assertEquals(1_180_000, until); assertEquals(180, RtpPolicy.remainingSeconds(until, 1_000_000));
        assertEquals(1, RtpPolicy.remainingSeconds(until, until - 1)); assertEquals(0, RtpPolicy.remainingSeconds(until, until));
        assertEquals(until, RtpPolicy.decodeCooldown(Long.toString(until))); assertEquals(0, RtpPolicy.decodeCooldown(null));
        assertEquals(181, RtpPolicy.remainingSeconds(until, 999_999), "Clock rollback never resets cooldown");
    }
    @Test void malformedCooldownAndOverflowDoNotResetState() {
        for (String raw : new String[]{"", "-1", "null", "{}", "1.5", "1e9", " 0", "01", "9223372036854775808"})
            assertThrows(IllegalArgumentException.class, () -> RtpPolicy.decodeCooldown(raw), raw);
        assertThrows(ArithmeticException.class, () -> RtpPolicy.deadline(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> RtpPolicy.deadline(-1));
        assertThrows(IllegalArgumentException.class, () -> RtpPolicy.remainingSeconds(0, -1));
    }
}
