package ru.warland.content;

import java.util.*;

/** Immutable, deterministic original blueprint. No world access or Minecraft bootstrap needed. */
public record BlockPlan(String id, List<Cell> cells, int minX, int minY, int minZ,
                        int maxX, int maxY, int maxZ) {
    public record Cell(int x, int y, int z, String block) {}
    public record Point(int x, int y, int z) {}

    public BlockPlan {
        Objects.requireNonNull(id);
        cells = List.copyOf(cells);
        if (cells.isEmpty() || cells.size() > 150_000) throw new IllegalArgumentException("Blueprint size");
        if (maxX - minX > 256 || maxY - minY > 64 || maxZ - minZ > 256) {
            throw new IllegalArgumentException("Blueprint bounds");
        }
    }

    public int width() { return maxX - minX + 1; }
    public int height() { return maxY - minY + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public int volume() { return Math.multiplyExact(Math.multiplyExact(width(), height()), depth()); }
    public Point volumePoint(int index) {
        if (index < 0 || index >= volume()) throw new IndexOutOfBoundsException(index);
        return new Point(minX + index % width(), minY + index / (width() * depth()),
                minZ + (index / width()) % depth());
    }
    public SortedMap<String, Integer> materials() {
        SortedMap<String, Integer> result = new TreeMap<>();
        for (Cell c : cells) result.merge(c.block().split("\\[", 2)[0], 1, Math::addExact);
        return Collections.unmodifiableSortedMap(result);
    }
    /** Stable algorithm identity; changing a blueprint requires a new id, not silent job migration. */
    public String fingerprint() {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            for (Cell c : cells) hash.update((c.x()+","+c.y()+","+c.z()+":"+c.block()+"\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static final class Builder {
        private final String id;
        private final Map<Point, Cell> cells = new HashMap<>();
        public Builder(String id) { this.id = id; }
        public Builder block(int x, int y, int z, String block) {
            if (!block.matches("[a-z0-9_]+:[a-z0-9_]+(\\[[a-z0-9_=,]+])?")) {
                throw new IllegalArgumentException("Invalid block: " + block);
            }
            cells.put(new Point(x,y,z), new Cell(x,y,z,block)); return this;
        }
        public Builder box(int x1,int y1,int z1,int x2,int y2,int z2,String block) {
            if (x2 < x1 || y2 < y1 || z2 < z1) throw new IllegalArgumentException("Reversed box");
            for (int y=y1;y<=y2;y++) for(int z=z1;z<=z2;z++) for(int x=x1;x<=x2;x++) block(x,y,z,block);
            return this;
        }
        /** Omit a blueprint voxel. This never schedules air or deletion in the real world. */
        public Builder omit(int x1,int y1,int z1,int x2,int y2,int z2) {
            for (int y=y1;y<=y2;y++) for(int z=z1;z<=z2;z++) for(int x=x1;x<=x2;x++) cells.remove(new Point(x,y,z));
            return this;
        }
        public BlockPlan build() {
            List<Cell> ordered = cells.values().stream().sorted(Comparator.comparingInt(Cell::y)
                    .thenComparingInt(Cell::z).thenComparingInt(Cell::x)).toList();
            if (ordered.isEmpty()) throw new IllegalArgumentException("Empty blueprint");
            return new BlockPlan(id, ordered,
                    ordered.stream().mapToInt(Cell::x).min().orElseThrow(), ordered.stream().mapToInt(Cell::y).min().orElseThrow(),
                    ordered.stream().mapToInt(Cell::z).min().orElseThrow(), ordered.stream().mapToInt(Cell::x).max().orElseThrow(),
                    ordered.stream().mapToInt(Cell::y).max().orElseThrow(), ordered.stream().mapToInt(Cell::z).max().orElseThrow());
        }
    }
}
