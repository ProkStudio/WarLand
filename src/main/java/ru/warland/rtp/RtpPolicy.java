package ru.warland.rtp;

import java.util.Set;
import java.util.random.RandomGenerator;

/** Pure, deliberately conservative landing and timing rules; no Minecraft/DB access. */
final class RtpPolicy {
    static final String WORLD = "minecraft:overworld";
    static final int MIN_RADIUS = 1_000, MAX_RADIUS = 3_000;
    static final long COOLDOWN_MILLIS = 180_000;
    static final int PAD_RADIUS = 1, NEIGHBOR_RADIUS = 2, CHUNK_MARGIN = 3;
    static final int MAX_PROPOSALS = 32, MAX_CHUNKS = 8, COLUMNS_PER_CHUNK = 4, MAX_PROBES = 32;
    static final long LOAD_NANOS = 4_000_000_000L, IO_NANOS = 5_000_000_000L;
    static final long SEARCH_NANOS = 30_000_000_000L, CHUNK_INTERVAL_NANOS = 1_000_000_000L;
    static final double MOVEMENT_SQUARED = 0.0025; // 5 cm of position jitter, not a whole block.

    private static final Set<String> HAZARDS = Set.of(
            "minecraft:water", "minecraft:lava", "minecraft:bubble_column",
            "minecraft:fire", "minecraft:soul_fire", "minecraft:magma_block",
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:wither_rose",
            "minecraft:powder_snow", "minecraft:campfire", "minecraft:soul_campfire",
            "minecraft:pointed_dripstone", "minecraft:cobweb", "minecraft:tnt",
            "minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway",
            "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:frosted_ice",
            "minecraft:slime_block", "minecraft:honey_block", "minecraft:tripwire",
            "minecraft:tripwire_hook", "minecraft:sculk_shrieker");

    record Column(int x, int z) {}
    record Cell(boolean air, boolean emptyCollision, boolean fullCube,
                boolean fluid, boolean hazard, boolean unstable) {}
    enum Rejection { NONE, WORLD, RANGE, CHUNK_EDGE, UNLOADED, HEIGHT, NOT_SURFACE,
        BORDER, PROTECTED, HAZARD, FLOOR, HEADROOM, COLLISION }

    interface Terrain {
        String dimension();
        boolean loaded();
        int bottomY();
        int topY(); // inclusive
        int surfaceY(int x, int z); // first air block over WORLD_SURFACE
        boolean insideBorder(int x, int z);
        boolean protectedColumn(int x, int z);
        Cell cell(int x, int y, int z);
        boolean collisionFree(int x, int y, int z);
    }

    private RtpPolicy() {}

    static Column sample(RandomGenerator random, int spawnX, int spawnZ) {
        double radius = Math.sqrt((double) MIN_RADIUS * MIN_RADIUS
                + random.nextDouble() * ((double) MAX_RADIUS * MAX_RADIUS - (double) MIN_RADIUS * MIN_RADIUS));
        double angle = random.nextDouble() * Math.PI * 2;
        // Final integer columns are always checked again; rounding is not authorization.
        return new Column((int) Math.floor(spawnX + 0.5 + Math.cos(angle) * radius),
                (int) Math.floor(spawnZ + 0.5 + Math.sin(angle) * radius));
    }

    static boolean inAnnulus(int spawnX, int spawnZ, int x, int z) {
        double dx = (double) x - spawnX, dz = (double) z - spawnZ;
        double squared = dx * dx + dz * dz;
        return squared >= (double) MIN_RADIUS * MIN_RADIUS && squared <= (double) MAX_RADIUS * MAX_RADIUS;
    }

    static boolean interior(int x, int z) {
        int localX = x & 15, localZ = z & 15;
        return localX >= CHUNK_MARGIN && localX < 16 - CHUNK_MARGIN
                && localZ >= CHUNK_MARGIN && localZ < 16 - CHUNK_MARGIN;
    }

    static boolean hazardous(String blockId) {
        return blockId == null || !blockId.startsWith("minecraft:")
                || HAZARDS.contains(blockId) || blockId.endsWith("_pressure_plate");
    }

    static Rejection evaluate(Terrain t, int spawnX, int spawnZ, int x, int y, int z) {
        if (!WORLD.equals(t.dimension())) return Rejection.WORLD;
        if (!inAnnulus(spawnX, spawnZ, x, z)) return Rejection.RANGE;
        if (!interior(x, z)) return Rejection.CHUNK_EDGE;
        if (!t.loaded()) return Rejection.UNLOADED;
        if (y <= t.bottomY() || (long) y + 2 > t.topY()) return Rejection.HEIGHT;
        if (t.surfaceY(x, z) != y) return Rejection.NOT_SURFACE;
        for (int dx = -NEIGHBOR_RADIUS; dx <= NEIGHBOR_RADIUS; dx++) {
            for (int dz = -NEIGHBOR_RADIUS; dz <= NEIGHBOR_RADIUS; dz++) {
                int px = x + dx, pz = z + dz;
                // Include the entire 5x5 safety footprint, not just the center.
                if (!t.insideBorder(px, pz)) return Rejection.BORDER;
                if (t.protectedColumn(px, pz)) return Rejection.PROTECTED;
                boolean pad = Math.abs(dx) <= PAD_RADIUS && Math.abs(dz) <= PAD_RADIUS;
                for (int dy = -1; dy <= 2; dy++) {
                    Cell cell = t.cell(px, y + dy, pz);
                    if (cell == null || cell.fluid() || cell.hazard()) return Rejection.HAZARD;
                    if (pad && dy == -1 && (!cell.fullCube() || cell.unstable())) return Rejection.FLOOR;
                    if (pad && dy >= 0 && (!cell.air() || !cell.emptyCollision())) return Rejection.HEADROOM;
                }
            }
        }
        return t.collisionFree(x, y, z) ? Rejection.NONE : Rejection.COLLISION;
    }

    static boolean containsBox(double west, double north, double east, double south,
                               double minX, double minZ, double maxX, double maxZ) {
        return Double.isFinite(west) && Double.isFinite(north) && Double.isFinite(east)
                && Double.isFinite(south) && Double.isFinite(minX) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxZ)
                && minX < maxX && minZ < maxZ && minX >= west && maxX <= east
                && minZ >= north && maxZ <= south;
    }

    static boolean moved(double ox, double oy, double oz, double x, double y, double z) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        double distance = dx * dx + dy * dy + dz * dz;
        return !Double.isFinite(distance) || distance > MOVEMENT_SQUARED;
    }

    static boolean expired(long started, long now, long budget) {
        // Subtraction handles nanoTime's signed wrap for these short, bounded intervals.
        return now - started >= budget;
    }

    static boolean hasBudget(int proposals, int chunks, int probes) {
        return proposals < MAX_PROPOSALS && chunks < MAX_CHUNKS && probes < MAX_PROBES;
    }

    static long decodeCooldown(String raw) {
        if (raw == null) return 0;
        // A JSON integer in the existing state table. Corruption must never grant a free retry.
        if (!raw.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Invalid RTP cooldown state");
        try { return Long.parseLong(raw); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid RTP cooldown state", error); }
    }

    static long deadline(long nowMillis) {
        if (nowMillis < 0) throw new IllegalArgumentException("Invalid clock");
        return Math.addExact(nowMillis, COOLDOWN_MILLIS);
    }

    static long remainingSeconds(long until, long nowMillis) {
        if (until < 0 || nowMillis < 0) throw new IllegalArgumentException("Invalid clock or cooldown");
        if (until <= nowMillis) return 0;
        long remaining = until - nowMillis;
        return remaining / 1_000 + (remaining % 1_000 == 0 ? 0 : 1);
    }
}
