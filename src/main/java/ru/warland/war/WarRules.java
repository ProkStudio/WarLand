package ru.warland.war;

import java.time.*;
import java.util.*;

/** Pure, bounded war rules. No clocks, database access or Minecraft objects. */
public final class WarRules {
    public static final long MOBILIZATION_MILLIS = 12 * 3_600_000L;
    public static final int CAPTURE_POINTS = 480, CAPTURES_PER_WINDOW = 3, SCORE_PER_CAPTURE = 20;
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private WarRules() {}

    public record Schedule(int hour, int hours) {
        public Schedule {
            if (hour < 0 || hour > 23 || hours < 1 || hours > 8)
                throw new IllegalArgumentException("Некорректное боевое окно");
        }
        public boolean open(long now, long starts, long ends) {
            if (now < starts || now >= ends) return false;
            int h = Instant.ofEpochMilli(now).atZone(MOSCOW).getHour();
            return Math.floorMod(h - hour, 24) < hours;
        }
        /** One key per window, including a window crossing Moscow midnight. */
        public String windowKey(long now) {
            ZonedDateTime time = Instant.ofEpochMilli(now).atZone(MOSCOW);
            return (time.getHour() < hour ? time.toLocalDate().minusDays(1) : time.toLocalDate()).toString();
        }
        public String label() {
            return String.format(Locale.ROOT, "%02d:00–%02d:00 МСК", hour, (hour + hours) % 24);
        }
    }

    public record Chunk(int x, int z) {}
    /** Includes window identity: progress can never leak into tomorrow's window. */
    public record CaptureKey(String war, String side, String dimension, int x, int z, String window) {
        public CaptureKey {
            Objects.requireNonNull(war); Objects.requireNonNull(side);
            Objects.requireNonNull(dimension); Objects.requireNonNull(window);
        }
    }

    public static String result(int attackScore, int defendScore) {
        long difference = (long) attackScore - defendScore;
        return Math.abs(difference) < SCORE_PER_CAPTURE ? "DRAW" : difference > 0 ? "ATTACKER_WIN" : "DEFENDER_WIN";
    }

    /** Fail closed on corrupted/oversized territory rather than unbounded scans. */
    public static boolean connected(Set<Chunk> chunks, Chunk capital) {
        if (chunks.isEmpty() || chunks.size() > 1024 || !chunks.contains(capital)) return false;
        Set<Chunk> visited = new HashSet<>();
        ArrayDeque<Chunk> queue = new ArrayDeque<>();
        visited.add(capital); queue.add(capital);
        while (!queue.isEmpty()) {
            Chunk current = queue.remove();
            for (Chunk next : List.of(new Chunk(current.x + 1, current.z), new Chunk(current.x - 1, current.z),
                    new Chunk(current.x, current.z + 1), new Chunk(current.x, current.z - 1))) {
                if (chunks.contains(next) && visited.add(next)) queue.add(next);
            }
        }
        return visited.size() == chunks.size();
    }
}
