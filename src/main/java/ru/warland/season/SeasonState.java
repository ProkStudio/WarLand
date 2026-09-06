package ru.warland.season;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded document. No world, inventory or economy data is owned here. */
public record SeasonState(int schema, long revision, long highWatermark, List<Season> seasons) {
    public static final int SCHEMA = 1;
    public static final int MAX_SEASONS = 32;
    public static final long DAY = 86_400_000L;
    public static final long MAX_TIME = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli();
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    public enum Phase { SCHEDULED, ACTIVE, FINAL_WEEK, FINALIZING, ARCHIVED, CANCELLED }
    public record Transition(Phase phase, long effectiveAt, long observedAt, String actor, String reason) {
        public Transition {
            Objects.requireNonNull(phase, "phase");
            require(effectiveAt > 0 && observedAt >= effectiveAt && observedAt <= MAX_TIME, "transition time");
            require(text(actor, 1, 80) && text(reason, 8, 160), "transition attribution");
        }
    }
    public record Season(String id, long startsAt, long endsAt, List<Transition> history) {
        public Season {
            require(id != null && id.matches("[a-z0-9][a-z0-9_-]{2,31}"), "season id");
            require(startsAt > 0 && startsAt < MAX_TIME && endsAt <= MAX_TIME - DAY, "season time");
            require(endsAt == endFor(startsAt), "season must last two calendar months");
            history = List.copyOf(Objects.requireNonNull(history, "history"));
            require(!history.isEmpty() && history.size() <= 5, "history limit");
            Transition first = history.getFirst();
            require(first.phase() == Phase.SCHEDULED && first.observedAt() < startsAt
                    && first.effectiveAt() == first.observedAt(), "scheduled transition");
            long lastObserved = first.observedAt();
            for (int i = 1; i < history.size(); i++) {
                Transition step = history.get(i);
                Phase previous = history.get(i - 1).phase();
                require(step.observedAt() >= lastObserved, "history rollback");
                if (step.phase() == Phase.CANCELLED) {
                    require(previous == Phase.SCHEDULED && i == history.size() - 1
                            && step.effectiveAt() == step.observedAt() && step.observedAt() < startsAt,
                            "cancellation boundary");
                } else {
                    require(next(previous) == step.phase(), "phase order");
                    require(step.effectiveAt() == due(step.phase(), startsAt, endsAt), "phase deadline");
                }
                lastObserved = step.observedAt();
            }
        }
        public Phase phase() { return history.getLast().phase(); }
        public boolean terminal() { return phase() == Phase.ARCHIVED || phase() == Phase.CANCELLED; }
    }

    public SeasonState {
        require(schema == SCHEMA && revision >= 0, "unsupported schema or revision");
        require(highWatermark >= 0 && highWatermark <= MAX_TIME, "clock watermark");
        seasons = List.copyOf(Objects.requireNonNull(seasons, "seasons"));
        require(seasons.size() <= MAX_SEASONS, "season archive limit");
        var ids = new HashSet<String>();
        long latestObserved = 0;
        int open = 0;
        for (Season season : seasons) {
            require(ids.add(season.id()), "duplicate season id");
            if (!season.terminal()) open++;
            latestObserved = Math.max(latestObserved, season.history().getLast().observedAt());
        }
        require(open <= 1 && highWatermark >= latestObserved, "overlapping season or stale watermark");
    }
    public static SeasonState empty() { return new SeasonState(SCHEMA, 0, 0, List.of()); }
    public Season current() { return seasons.stream().filter(s -> !s.terminal()).findFirst().orElse(null); }
    public Season find(String id) { return seasons.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null); }
    public static long endFor(long start) { return Instant.ofEpochMilli(start).atZone(ZONE).plusMonths(2).toInstant().toEpochMilli(); }
    static Phase next(Phase phase) {
        return switch (phase) {
            case SCHEDULED -> Phase.ACTIVE;
            case ACTIVE -> Phase.FINAL_WEEK;
            case FINAL_WEEK -> Phase.FINALIZING;
            case FINALIZING -> Phase.ARCHIVED;
            default -> null;
        };
    }
    public static long due(Phase phase, long start, long end) {
        return switch (phase) {
            case ACTIVE -> start;
            case FINAL_WEEK -> end - 7 * DAY;
            case FINALIZING -> end;
            case ARCHIVED -> end + DAY;
            default -> throw new IllegalArgumentException("No automatic deadline");
        };
    }
    static boolean text(String value, int min, int max) {
        return value != null && value.length() >= min && value.length() <= max
                && value.equals(value.strip()) && value.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0xA7);
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
