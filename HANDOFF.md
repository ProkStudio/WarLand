# WarLand — точка продолжения

Обновлено 6 сентября 2026, продолжение после 11:28 МСК. **Полного релиза пока нет.** Этот файл сохраняет состояние при обрыве сессии; он не означает фоновую работу после её завершения.

## Начинать отсюда

**[Текущий подробный конспект](https://github.com/ProkStudio/WarLand/blob/feature/release-continuation/docs/PROGRESS_2026-09-06_CONTINUATION.md)** — код, проверки, фактический статус, оставшиеся блокеры и пути доказательств.

- `feature/release-continuation` — новая общая ветка продолжения от `feature/auth-runtime` (`ef475977`).
- `fix/moderation-auth-continuation` — защита offline-владельца и очередь модерации, работа в отдельной ветке.
- `fix/treasury-auth-continuation` — session-bound treasury, работа в отдельной ветке.
- `fix/starter-grant-continuation` — сохранение входа после смены стартового баланса, работа в отдельной ветке.
- `feature/auth-runtime` / [PR10](https://github.com/ProkStudio/WarLand/pull/10) — предыдущее продолжение авторизации, пока draft.
- `development/initial-release` — предыдущая интеграционная alpha. `main` по-прежнему содержит исходное ТЗ и этот указатель, не готовый сервер.

## Что уже изменилось в этом продолжении

Добавлены приватная консоль systemd без RCON/TCP, защищённый Unix socket с проверкой личности ОС, инструкции оператора и тесты. Первые22 console-теста и полная Python suite76/76 прошли на VPS; ещё две exit/shutdown-регрессии прошли локально. Реальная Fabric console/private-bootstrap/restart проверка запущена только на отдельной синтетической копии; окончательный результат уточнять в конспекте и сохранённом JSON, не считать сам запуск успехом.

Сборка предыдущего исправления Unicode-ввода600938a подтверждена: Java346 обнаружены/342 прошли/4 старых пропуска/0 ошибок, Python54. JAR SHA256 `d21384a0707d4132a7679f91aecaf1e58ee72ee354bf36cb00cc4c8a9694ff76`.

Аудит выявил дополнительные queued political mutations после revocation, starter-grant конфликт после изменения конфига, отсутствие auth/profile-ready фильтра в захватах и request-time bootstrap expiry. Исправления/проверки не подменять декларациями готовности.

## Что сохранять

Рабочие Paper и прежний приватный Fabric staging активны; их миры/конфиги не заменены. Старые dirty worktrees и QA-папки не перезаписывать. На VPS новая рабочая копия `/opt/warland-build/release-continuation-20260906`; console-проверка `private-console-qa-600938a-v1` и `private-console-600938a-v1.{log,pid,exit}` в том же корне. Только одна тяжёлая сборка/тестовая JVM одновременно: сервер4GiB RAM.

Ни реального bootstrap, ни привязки владельца, ни OP не выдавалось. Никнейм не доказывает личность. Секреты, базы, миры и player data в публичный GitHub не копировать. Непройденные Mojang/GUI/load/бета10/20/30, inventory/crash/market, остальные игровые этапы и Paper→Fabric/UUID миграция остаются обязательными.

[Предыдущий encrypted-auth checkpoint](https://github.com/ProkStudio/WarLand/blob/feature/auth-runtime/docs/PROGRESS_2026-09-06_ENCRYPTED_AUTH.md) содержит успешные синтетические encrypted43be057 проверки и их hashes. Не выдавать результаты старого JAR за приёмку нового интегрированного кода.
