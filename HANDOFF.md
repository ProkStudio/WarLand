# WarLand — точка продолжения

Обновлено6сентября2026 после публикации игровой альфы.

**Ограниченная 0.1.0-alpha.2 уже опубликована и запущена. Полное ТЗ/стабильный релиз ещё не завершены.**

Начинать с [ALPHA_PROGRESS.md](ALPHA_PROGRESS.md): точные коммиты/хеши, server+GUI acceptance, остаток задач, приватные пути доказательств и откат. Это сохранённое состояние, а не обещание фоновой работы после закрытия диалога.

- Сервер: `201.51.10.116:25565`.
- [Релиз и клиентский ZIP](https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.2).
- [Установка и регистрация](https://github.com/ProkStudio/WarLand/blob/release/0.1.0-alpha.2/docs/ALPHA_JOIN.md).
- Release branch `release/0.1.0-alpha.2`, release commit `7a038011a8a292b8fdb22b4becc8f4adfbc87668`; код игры `2a89fa966f0f3fabf90d785fc22aaebb52655db1`.
- Активный `warland-alpha.service`; Paper/staging остановлены, данные и приватные резервные копии сохранены. Новый alpha-мир, без UUID-миграции.
- Java438passed/4skips/0failures, Python156passed, CI успешен. Native wire auth/restart/duplicates и реальный GUI register→world→balance→reconnect-login прошли. Это не полная бета/все системы ТЗ.
- Owner не назначается по нику; reserved `egorkrid666` требует приватного одноразового proof. Код не публикуется.

Старые ссылки сохранены для истории: [предыдущее продолжение](https://github.com/ProkStudio/WarLand/blob/feature/release-continuation/docs/PROGRESS_2026-09-06_CONTINUATION.md), draftPR10/11 и feature-ветки. Их заявления «Paper активен», «compileTestJava ещё не исправлен», «GUI не проверен» устарели для нынешнего среза; не переносить их в новый статус без проверки.

Незавершённые market/inventory/crash, building purchases/capture, aviation/HUD, content, moderation/recovery и beta10/20/30 остаются в следующем этапе. Не снимать feature flags ради галочки. Не удалять worlds/accounts/keys/backups/dirty worktrees и не публиковать приватные данные.
