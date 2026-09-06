package ru.warland.war;

import java.util.*;
import ru.warland.war.WarRules.CaptureKey;

/** Main-thread-only progress. Contested, absent and closed-window entries are discarded. */
final class CaptureProgress {
    private record Progress(int points, long observed) {}
    private final Map<CaptureKey, Progress> entries = new HashMap<>();

    boolean advance(CaptureKey key, boolean defenderOnline, long now) {
        Progress previous = entries.get(key);
        int points = previous == null || now < previous.observed || now - previous.observed > 5000
                ? 0 : previous.points;
        // Repeated invocation in the same millisecond cannot accelerate a capture.
        if (previous != null && now == previous.observed) return points >= WarRules.CAPTURE_POINTS;
        points = Math.min(WarRules.CAPTURE_POINTS, points + (defenderOnline ? 4 : 1));
        entries.put(key, new Progress(points, now));
        return points >= WarRules.CAPTURE_POINTS;
    }
    void retain(Set<CaptureKey> active) { entries.keySet().retainAll(active); }
    void remove(CaptureKey key) { entries.remove(key); }
    int size() { return entries.size(); }
}
