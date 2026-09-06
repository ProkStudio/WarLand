package ru.warland.help;

import java.util.List;
import java.util.function.Predicate;

/** Static documentation only. Never executes examples or reads private state. */
public final class OwnerHelpCatalog {
    private OwnerHelpCatalog() {}
    public static final int PAGE_SIZE = 4;
    public record Entry(String path, String usage, String description, String example) {
        public List<String> nodes() { return List.of(path.split(" ")); }
    }
    private static Entry e(String path, String usage, String description, String example) {
        return new Entry(path, usage, description, example);
    }
    public static final List<Entry> ENTRIES = List.of(
        e("wladmin diag", "/wladmin diag", "Готовность, средний тик и загруженные модули; без секретов конфигурации.", "/wladmin diag"),
        e("wladmin reports", "/wladmin reports", "Открытые жалобы; совместимый вариант /staff reports.", "/wladmin reports"),
        e("wladmin resolve", "/wladmin resolve <id> <причина>", "Закрыть жалобу с записью причины; также /staff resolve. ID взять из списка, пример не запускать вслепую.", "/wladmin resolve 12 Проверено по журналу событий"),
        e("staff", "/staff", "Существующая панель персонала; эта справка остаётся в чате. Роль OWNER определяется входом, не ником/OP.", "/staff"),
        e("staff diag", "/staff diag", "Готовность и средний тик с аудитом обращения.", "/staff diag"),
        e("staff reports", "/staff reports", "Показать открытые жалобы через чат.", "/staff reports"),
        e("staff resolve", "/staff resolve <id> <причина>", "Закрыть выбранную жалобу с причиной; id >= 1.", "/staff resolve 12 Жалоба проверена и обработана"),
        e("staff role", "/staff role <UUID> <MODERATOR|CURATOR|TECHADMIN>", "Выдать сохранённую роль только проверенному UUID. OWNER так не назначается. MODERATOR: наказания до 24ч; CURATOR: расширенная модерация; TECHADMIN: диагностика.", "/staff role <проверенный-UUID> MODERATOR"),
        e("staff revoke", "/staff revoke <UUID>", "Снять дополнительную роль персонала. Не снимает привязку владельца.", "/staff revoke <проверенный-UUID>"),
        e("staff ban", "/staff ban <онлайн-имя|UUID> <минуты> <причина>", "Блокировка 0–525600 минут; 0 = бессрочно. Причина до 300 символов. Офлайн-цель — UUID.", "/staff ban TestPlayer 60 Подтверждённое нарушение правил"),
        e("staff mute", "/staff mute <онлайн-имя|UUID> <минуты> <причина>", "Ограничить чат: 0–525600 минут; 0 = бессрочно. Причина до 300 символов.", "/staff mute TestPlayer 15 Повторный спам после предупреждения"),
        e("staff kick", "/staff kick <онлайн-имя> <причина>", "Отключить онлайн-игрока с причиной до 300 символов; не блокирует повторный вход.", "/staff kick TestPlayer Нарушение правил поведения"),
        e("staff unban", "/staff unban <онлайн-имя|UUID> <причина>", "Снять блокировку. Для заблокированного офлайн-игрока использовать проверенный UUID.", "/staff unban <проверенный-UUID> Апелляция проверена и одобрена"),
        e("staff unmute", "/staff unmute <онлайн-имя|UUID> <причина>", "Снять ограничение чата с обязательной причиной до 300 символов.", "/staff unmute TestPlayer Решение по апелляции принято"),
        e("capital status", "/capital status", "Текущее состояние размещения столицы. Ничего не строит.", "/capital status"),
        e("capital resume", "/capital resume <причина>", "Возобновить размещение столицы после ручной проверки. Меняет блоки; сначала backup и проверка состояния.", "/capital resume План и резервная копия проверены"),
        e("capital budget", "/capital budget <8–256>", "Сохранить лимит размещения блоков за пакет. Большие значения увеличивают нагрузку.", "/capital budget 32"),
        e("citybuild purchases", "/citybuild purchases <true|false>", "ОПАСНЫЙ ЭКСПЕРИМЕНТ: переключатель покупок зданий, escrow неатомарен. Оставить false до recovery/crash-приёмки; наличие команды не означает готовность.", "/citybuild purchases false"),
        e("citybuild recover", "/citybuild recover <job-id> <причина>", "Запросить сверку строительной работы, не гарантированный возврат. Сначала вручную проверить инвентарь, блоки и платёж.", "/citybuild recover <проверенный-job-id> Инвентарь блоки и платёж сверены"),
        e("arsenal issue", "/arsenal issue <профиль> <причина>", "Тестовая выдача оружия СЕБЕ: ak74, sidearm, smg, marksman, shotgun. Причина 8–160 символов; живой игрок в выживании, вне техники, без inventory-lock. Новый ствол пуст.", "/arsenal issue ak74 Проверка оружия на тестовом полигоне"),
        e("gunfocus", "/gunfocus <STEADY|QUICKLOAD|NONE> <причина>", "Изменить фокус оружия в основной руке. Причина 8–160 символов; те же ограничения выживания. Не готовая система прогрессии.", "/gunfocus NONE Сброс тестового фокуса оружия"),
        e("vehicle grant", "/vehicle grant <онлайн-игрок> <причина>", "Тестовая выдача прототипа техники. Причина 8–160 символов; лимиты ангара действуют. Это не магазин и не завершённая авиация.", "/vehicle grant TestPlayer Выдача для проверки на полигоне"),
        e("vehicle recover", "/vehicle recover <UUID-техники> <причина>", "Только RECOVERY → PARKED в сохранённой точке. Не возвращает уничтоженную технику, не добавляет топливо/прочность. Причина 8–160 символов.", "/vehicle recover <проверенный-UUID-техники> Состояние прототипа вручную проверено"),
        e("season", "/season", "Сохранённое расписание и фаза сезона; автоматического вайпа/наград нет.", "/season"),
        e("season history", "/season history", "Показать историю переходов без изменения миров, денег и инвентаря.", "/season history"),
        e("season schedule", "/season schedule <id> <ГГГГ-ММ-ДД> <причина>", "Начало в 00:00 МСК, от 5 минут до 366 дней в будущем; один текущий сезон. ID: 3–32 символа a-z/0-9/_/-. Причина 8–160 символов. Заменить дату в примере.", "/season schedule season_test <будущая-дата> Согласованное расписание тестового сезона"),
        e("season cancel", "/season cancel <id> <причина>", "Отменить только ещё не начавшийся сезон, сохранив историю. Причина 8–160 символов.", "/season cancel season_test Расписание перенесено по согласованию")
    );
    public static List<Entry> visible(Predicate<List<String>> available) {
        return ENTRIES.stream().filter(e -> available.test(e.nodes())).toList();
    }
    public static int pages(List<Entry> visible) { return Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE); }
    public static List<Entry> page(List<Entry> visible, int page) {
        if (page < 1 || page > pages(visible)) throw new IllegalArgumentException("Invalid help page");
        int start = (page - 1) * PAGE_SIZE;
        return List.copyOf(visible.subList(start, Math.min(start + PAGE_SIZE, visible.size())));
    }
}
