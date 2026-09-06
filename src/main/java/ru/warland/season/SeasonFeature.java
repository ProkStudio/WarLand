package ru.warland.season;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.warland.api.Feature;
import ru.warland.api.WarLandApi;
import static net.minecraft.server.command.CommandManager.*;

/** Read-only season calendar + durable automatic lifecycle. It NEVER wipes or distributes rewards. */
public final class SeasonFeature implements Feature {
    private static final Logger LOG = LoggerFactory.getLogger("warland-season");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(SeasonState.ZONE);
    private final Clock clock;
    private WarLandApi api;
    private SeasonStore store;
    private long ticks;
    public SeasonFeature() { this(Clock.systemUTC()); }
    SeasonFeature(Clock clock) { this.clock = clock; }

    @Override public void initialize(WarLandApi api) {
        this.api = api;
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(literal("season")
                .executes(c -> show(c.getSource()))
                .then(literal("history").executes(c -> history(c.getSource())))
                .then(literal("schedule").requires(this::owner)
                    .then(argument("id", StringArgumentType.word())
                        .then(argument("date", StringArgumentType.word())
                            .then(argument("reason", StringArgumentType.greedyString()).executes(c -> schedule(c.getSource(),
                                StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "date"),
                                StringArgumentType.getString(c, "reason")))))))
                .then(literal("cancel").requires(this::owner)
                    .then(argument("id", StringArgumentType.word())
                        .then(argument("reason", StringArgumentType.greedyString()).executes(c -> cancel(c.getSource(),
                            StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "reason"))))))));
    }
    @Override public void serverStarted(MinecraftServer server) {
        ticks = 0;
        store = new SeasonStore(() -> api.getState(SeasonStore.NAMESPACE, SeasonStore.KEY),
                json -> api.setState(SeasonStore.NAMESPACE, SeasonStore.KEY, json), server::execute,
                error -> LOG.error("Season storage/lifecycle failed; no wipe or rewards will run", error));
        store.load(this::advance);
    }
    @Override public void tick(MinecraftServer server) {
        if (++ticks % 20 == 0) advance();
    }
    @Override public void stopped() { if (store != null) store.close(); }
    private void advance() {
        if (!available() || !store.idle()) return;
        store.change(s -> SeasonRules.advance(s, clock.instant()), this::announce,
                error -> LOG.error("Season transition rejected; persisted state retained", error));
    }
    private boolean available() { return api.ready() && store != null && store.ready(); }
    private boolean ready(ServerCommandSource source) {
        if (available()) return true;
        source.sendError(Text.literal("Календарь сезонов недоступен. Мир, инвентари и деньги не изменены."));
        return false;
    }
    private boolean owner(ServerCommandSource source) { return api.staff(source, "owner"); }
    private int schedule(ServerCommandSource source, String id, String date, String reason) {
        if (!owner(source) || !ready(source)) return 0;
        final long start;
        try {
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            start = LocalDate.parse(date).atStartOfDay(SeasonState.ZONE).toInstant().toEpochMilli();
        } catch (RuntimeException invalid) {
            source.sendError(Text.literal("Дата начала: ГГГГ-ММ-ДД, в 00:00 МСК.")); return 0;
        }
        store.change(s -> SeasonRules.schedule(s, id, start, actor(source), reason, clock.instant()), change -> {
            reply(source, change.changed() ? "Расписание сохранено. Автоматического вайпа и наград нет." : "Это расписание уже сохранено; повтор ничего не изменил.");
            announce(change);
        }, error -> rejected(source, error));
        return 1;
    }
    private int cancel(ServerCommandSource source, String id, String reason) {
        if (!owner(source) || !ready(source)) return 0;
        store.change(s -> SeasonRules.cancel(s, id, actor(source), reason, clock.instant()), change -> {
            reply(source, change.changed() ? "Ещё не начавшийся сезон отменён. История сохранена." : "Сезон уже отменён.");
            announce(change);
        }, error -> rejected(source, error));
        return 1;
    }
    private static String actor(ServerCommandSource source) {
        return source.getPlayer() == null ? "console" : source.getPlayer().getUuidAsString();
    }
    private void rejected(ServerCommandSource source, Throwable error) {
        LOG.warn("Season command rejected", error);
        source.sendError(Text.literal(store.ready() ? "Не выполнено: " + error.getMessage()
                : "Сохранение не подтверждено. Календарь заблокирован до безопасной перезагрузки; проверьте /season после восстановления."));
    }
    private int show(ServerCommandSource source) {
        if (!ready(source)) return 0;
        SeasonState state = store.snapshot();
        SeasonState.Season season = state.current();
        if (season == null && !state.seasons().isEmpty()) season = state.seasons().getLast();
        if (season == null) { reply(source, "Сезон ещё не запланирован. Автоматического вайпа нет."); return 1; }
        String status = summary(season);
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { reply(source, status); return 1; }
        var entries = new ArrayList<WarLandApi.MenuEntry>();
        entries.add(entry(Items.CLOCK, season.id() + " · " + label(season.phase()), () -> api.reply(player, status)));
        entries.add(entry(Items.BOOK, "Завершение и архив без удаления данных", () -> api.reply(player,
                "За неделю до конца — объявление финала; затем 24 часа на подведение итогов и архив календаря. Рейтинги и награды пока не подключены. Деньги, вещи, территории и мир НЕ сбрасываются.")));
        entries.add(entry(Items.PAPER, "История сезонов", () -> history(player.getCommandSource())));
        api.menu(player, "WarLand · сезоны", entries);
        return 1;
    }
    private int history(ServerCommandSource source) {
        if (!ready(source)) return 0;
        List<SeasonState.Season> seasons = store.snapshot().seasons();
        if (seasons.isEmpty()) reply(source, "История сезонов пуста.");
        for (SeasonState.Season season : seasons) reply(source, summary(season));
        return 1;
    }
    private void announce(SeasonStore.Change change) {
        if (!change.changed() || !api.ready()) return;
        SeasonState.Season season = change.after().seasons().getLast();
        // A downtime catch-up commits every missed transition but announces only the latest phase.
        String message = summary(season) + " · /season. Вайпа и выдачи наград нет.";
        for (var player : api.server().getPlayerManager().getPlayerList()) api.reply(player, message);
        var transition = season.history().getLast();
        api.audit(transition.actor(), "season." + season.phase().name().toLowerCase(java.util.Locale.ROOT),
                season.id() + ":revision=" + change.after().revision());
    }
    static String summary(SeasonState.Season season) {
        return season.id() + " · " + label(season.phase()) + " · "
                + TIME.format(java.time.Instant.ofEpochMilli(season.startsAt())) + " — "
                + TIME.format(java.time.Instant.ofEpochMilli(season.endsAt())) + " МСК";
    }
    static String label(SeasonState.Phase phase) {
        return switch (phase) {
            case SCHEDULED -> "Запланирован";
            case ACTIVE -> "Идёт сезон";
            case FINAL_WEEK -> "Финальная неделя";
            case FINALIZING -> "Подведение итогов (без расчёта рейтингов)";
            case ARCHIVED -> "Календарь в архиве";
            case CANCELLED -> "Отменён до начала";
        };
    }
    private static void reply(ServerCommandSource source, String text) {
        source.sendFeedback(() -> Text.literal("WarLand » " + text), false);
    }
    private static WarLandApi.MenuEntry entry(Item item, String label, Runnable action) {
        ItemStack icon = new ItemStack(item);
        icon.set(DataComponentTypes.CUSTOM_NAME, Text.literal(label));
        return new WarLandApi.MenuEntry(icon, action);
    }
}
