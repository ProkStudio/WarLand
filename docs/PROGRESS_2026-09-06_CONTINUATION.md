# WarLand — автономное продолжение 6 сентября

Checkpoint после 08:40 UTC. Это состояние выполненных действий и точка продолжения, не обещание фоновой работы. **Полного релиза пока нет.**

## Интеграция

Новая общая ветка `feature/release-continuation` создана от `feature/auth-runtime` (`ef475977`). Исходный PR10 остаётся draft. Нельзя перепутать старые dirty checkouts на VPS с этой веткой.

## Сделано и проверено

- `2d3cb75`: `tools/private_console.py`, systemd-example и `docs/PRIVATE_CONSOLE.md`. Приватный Linux Unix socket, без TCP/RCON, owner/root SO_PEERCRED, каталог0700/socket0600, non-root child, flock, защита ссылок/подмены inode, bounded framing/queue и неблокирующий stdin. `queued` не означает успех Minecraft-команды.
- 22 теста этого изменения прошли локально; точные файлы сверены SHA256 и полный Python suite **76/76** прошёл на VPS.
- Добавлены ещё две регрессии: сохранение nonzero exit ребёнка и возврат124 при вынужденной остановке. Все24 console-теста вместе прошли локально. Полная опубликованная suite после этих двух тестов ещё должна быть прогнана.
- `9b780b3`: новый `tools/private_console_smoke.py` для двух циклов настоящего Fabric/console/private-bootstrap/stop на строго отдельной синтетической копии. Скрипт SHA256 `dea65dd1754dfb3622481e5cc4b28cc9b8f2a25dd130f8e1abffb4ab87088589`.
- При возобновлении подтверждена завершённая сборка `600938a`: Java346 обнаружены/342 успешны/4 старых пропуска/0 ошибок; Python54. Исполняемый JAR SHA256 `d21384a0707d4132a7679f91aecaf1e58ee72ee354bf36cb00cc4c8a9694ff76`.

## Console live-проверка: результат уточнить

Запущена под непривилегированным build-user с общей build.lock, timeout330. Runtime600938a + scripts9b780b3; это НЕ проверка будущих Java-изменений.

На VPS под `/opt/warland-build`:
- исходники `release-continuation-20260906`;
- `private-console-600938a-v1.{log,pid,exit}`;
- свежая копия `private-console-qa-600938a-v1` и `private-console-result.json`;
- источник копирования только `release-qa/final-runtime`, не production/staging.

Последнее наблюдение: второй Fabric-процесс дошёл до `Done`, `WarLand ready`, настоящая консоль ответила `warland status`. Это ещё НЕ полный итог. При продолжении сначала прочитать exit/JSON; не перезапускать поверх имеющихся доказательств. Синтетические plaintext proof удаляются, реальный владелец/OP не назначается.

## Исправления в работе — не считать готовыми

- `fix/moderation-auth-continuation`: durable offline-owner и авторизация очереди модерации; отдельный исполнитель, без запуска Gradle.
- `fix/treasury-auth-continuation`: nonce/session lease для отложенного deposit/withdraw, сохранение публикации уже committed данных; отдельный исполнитель.
- `fix/starter-grant-continuation`: onJoin после изменения стартового баланса. Generic Store.change должен сохранить проверку конфликтов; нужен отдельный grant-if-absent.

Полученные коммиты сначала просмотреть и объединить, затем выполнить один точный clean test build и свежую runtime-матрицу. Несколько тяжёлых JVM одновременно на4GiB VPS не запускать.

## Дополнительные подтверждённые исходниками дефекты

Независимый read-only audit ef475977, без live-воспроизведения:
1. Смена config.startingBalance ломает повторный starter:<uuid> (конфликт delta) и вход старого игрока. Исправляется выше.
2. Помимо treasury, create/claim/invite/join/leave/tax/rank/defineRank/pact и declare/peace/surrender могут выполнить queued мутацию после revocation; одного подавления callback недостаточно. Распространить lease на эти пользовательские границы, не ломая system/offline accounting и committed cache refresh.
3. WarService считает участников без проверки profileReady/authorization; queued capture тоже нуждается в lease. `enableWarCapture=false` сохранять.
4. Bootstrap expiry сравнивается с моментом запроса, переданным через KDF/queue, а не временем commit; проверить строгую границу fake-clock/delayed-worker тестом.
5. Integrated single-player предполагаемо несовместим с server-only CoreRuntime; не рекламировать companion как офлайн-demo без настоящей клиентской проверки.

## Без изменений

Рабочие Paper и исходный private Fabric staging активны, не заменялись. Старые миры/конфиги/dirty worktrees сохранены. Секреты, БД, миры, личные данные не публикуются. Весь текущий код остаётся alpha. Настоящие Mojang/client GUI, бета10/20/30, inventory/crash/market, полные игровые этапы и Paper→Fabric/UUID миграция не закрыты этой работой. Предыдущая encrypted-auth история: `docs/PROGRESS_2026-09-06_ENCRYPTED_AUTH.md`.
