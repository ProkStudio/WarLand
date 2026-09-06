package ru.warland.season;

import java.time.Instant;
import java.util.ArrayList;
import static ru.warland.season.SeasonState.*;

/** Deterministic lifecycle. Clock corrections never rewind phases or reopen archived IDs. */
public final class SeasonRules {
    private SeasonRules() {}

    public static SeasonState schedule(SeasonState state, String id, long startsAt,
                                       String actor, String reason, Instant instant) {
        long now = now(state, instant);
        Season existing = state.find(id);
        if (existing != null) {
            Transition original = existing.history().getFirst();
            require(existing.startsAt() == startsAt && original.actor().equals(actor)
                    && original.reason().equals(reason), "Этот идентификатор уже использован с другими параметрами.");
            return state; // Retrying an acknowledged or ambiguous write never duplicates a season.
        }
        require(state.current() == null, "Сначала дождитесь архивации текущего сезона.");
        require(state.seasons().size() < MAX_SEASONS, "Архив заполнен: нужна согласованная миграция без удаления истории.");
        require(startsAt >= now + 300_000 && startsAt <= now + 366 * DAY,
                "Начало: от 5 минут до 366 дней в будущем.");
        require(text(actor, 1, 80) && text(reason, 8, 160), "Причина: 8–160 символов без управляющих знаков.");
        var seasons = new ArrayList<>(state.seasons());
        seasons.add(new Season(id, startsAt, endFor(startsAt),
                java.util.List.of(new Transition(Phase.SCHEDULED, now, now, actor, reason))));
        return updated(state, now, seasons);
    }

    public static SeasonState cancel(SeasonState state, String id, String actor, String reason, Instant instant) {
        long now = now(state, instant);
        Season season = state.find(id);
        require(season != null, "Сезон не найден.");
        require(text(actor, 1, 80) && text(reason, 8, 160), "Причина: 8–160 символов без управляющих знаков.");
        if (season.phase() == Phase.CANCELLED) return state;
        require(season.phase() == Phase.SCHEDULED && now < season.startsAt(), "Отменять можно только ещё не начавшийся сезон.");
        var history = new ArrayList<>(season.history());
        history.add(new Transition(Phase.CANCELLED, now, now, actor, reason));
        var seasons = new ArrayList<>(state.seasons());
        seasons.set(seasons.indexOf(season), new Season(id, season.startsAt(), season.endsAt(), history));
        return updated(state, now, seasons);
    }

    public static SeasonState advance(SeasonState state, Instant instant) {
        long now = now(state, instant);
        Season season = state.current();
        if (season == null) return state;
        var history = new ArrayList<>(season.history());
        Phase phase = season.phase();
        while (next(phase) != null && now >= due(next(phase), season.startsAt(), season.endsAt())) {
            phase = next(phase);
            history.add(new Transition(phase, due(phase, season.startsAt(), season.endsAt()), now,
                    "server", "Автоматический переход по сохранённому расписанию"));
        }
        if (history.size() == season.history().size()) return state; // No periodic idle writes.
        var seasons = new ArrayList<>(state.seasons());
        seasons.set(seasons.indexOf(season), new Season(season.id(), season.startsAt(), season.endsAt(), history));
        return updated(state, now, seasons);
    }

    private static long now(SeasonState state, Instant instant) {
        long time = instant.toEpochMilli();
        require(time > 0 && time <= MAX_TIME, "Время сервера вне поддерживаемого диапазона.");
        return Math.max(time, state.highWatermark());
    }
    private static SeasonState updated(SeasonState old, long now, java.util.List<Season> seasons) {
        return new SeasonState(SCHEMA, Math.addExact(old.revision(), 1), now, seasons);
    }
}
