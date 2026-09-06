package ru.warland.content;

/** Fresh, bounded authorization of every voxel and its foundation; never loads a world. */
final class BuildingSiteRules {
    private static final long MAX_CHECKS = 1024;
    private BuildingSiteRules() {}

    @FunctionalInterface
    interface PermissionAt { boolean allowed(int x, int y, int z); }

    static boolean allowed(BlockPlan plan, int x, int y, int z, PermissionAt permission) {
        if (plan == null || permission == null) return false;
        long width = (long) plan.maxX() - plan.minX() + 1;
        long depth = (long) plan.maxZ() - plan.minZ() + 1;
        long height = (long) plan.maxY() - plan.minY() + 2; // includes foundation
        if (width <= 0 || depth <= 0 || height <= 1 || width > MAX_CHECKS
                || depth > MAX_CHECKS || height > MAX_CHECKS
                || width * depth * height > MAX_CHECKS) return false;
        long minX = (long) x + plan.minX(), maxX = (long) x + plan.maxX();
        long minY = (long) y + plan.minY() - 1, maxY = (long) y + plan.maxY();
        long minZ = (long) z + plan.minZ(), maxZ = (long) z + plan.maxZ();
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
                || minY < Integer.MIN_VALUE || maxY > Integer.MAX_VALUE
                || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) return false;
        for (long yy = minY; yy <= maxY; yy++)
            for (long zz = minZ; zz <= maxZ; zz++)
                for (long xx = minX; xx <= maxX; xx++)
                    if (!permission.allowed((int) xx, (int) yy, (int) zz)) return false;
        return true;
    }
}
