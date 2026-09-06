# WarLand — точка продолжения

Обновлено 6 сентября 2026, после проверки `aa9c695`. **Полного релиза пока нет.** Это сохранённое состояние для продолжения при обрыве сессии, не обещание фоновой работы после её завершения.

## Начинать отсюда

**[Подробный конспект](https://github.com/ProkStudio/WarLand/blob/feature/release-continuation/docs/PROGRESS_2026-09-06_CONTINUATION.md)** — фактические проверки, блокеры и пути доказательств.

- Основная рабочая ветка: `feature/release-continuation`, [draft PR11](https://github.com/ProkStudio/WarLand/pull/11) → `feature/auth-runtime`.
- Новые Java-изменения опубликованы тремя коммитами: `be37e553`, `bd0c1adf`, `aa9c6954`. Store сохраняет первоначальный starter-grant при изменении конфига; generic idempotency не ослаблена. Транзакции государств получают захваченную connection/nonce/profile-ready lease и перепроверяют её на DB-worker. Уже committed cache refresh не отменяется вместе с наблюдающим future.
- `fix/treasury-auth-continuation` и `fix/starter-grant-continuation` НЕ содержат законченных исправлений: большие публикации не завершились, их HEAD проверен неизменным. Работу восстановили непосредственно в общей ветке. Не cherry-pick эти ветки как готовый результат.
- `fix/moderation-auth-continuation` и `feature/qa-kit-continuation`: отдельные задачи; результат ещё нужно проверить по реальным коммитам/тестам.
- `feature/auth-runtime` / [draft PR10](https://github.com/ProkStudio/WarLand/pull/10) — предыдущее продолжение. `development/initial-release` — предыдущая интеграционная alpha. `main` содержит исходное ТЗ и этот указатель, не готовый сервер.

## Проверки и текущая ошибка

- Новые четыре production Java-файла и два test-файла сверены SHA256 между подготовленной копией и GitHub/VPS. Production Java `aa9c695` скомпилировалась, включая `MinecraftServer.isOnThread()`.
- Первый `clean test build` остановился на compileTestJava: неоднозначный generic `assertTrue(await(db.tx(...)))` в StarterAndLeaseTest. Это ошибка нового теста, исправляется явным boolean. JUnit в этой попытке НЕ запускался; нельзя считать отсутствие XML успехом. Лог `/opt/warland-build/continuation-aa9c695-build.log`, exit `java=1 python=0`. Python **78/78** прошёл.
- Baseline `600938a`: Java346 обнаружены/342 прошли/4 старых пропуска/0 ошибок, Python54. JAR SHA256 `d21384a0707d4132a7679f91aecaf1e58ee72ee354bf36cb00cc4c8a9694ff76`.
- Реальная изолированная Fabric console/private-bootstrap/двойной restart проверка baseline **завершилась успешно**. JSON SHA256 `24417961fcaad90d3d4dd53c23c86f29f65c0283f8d60a8e1fab9d60032d6e81`.
- Свежая encrypted600938a матрица **завершилась успешно**: forged READY/stale nonce/mismatch confirmation отвергнуты без durable effects; регистрация → PLAY, restart, wrong password deny, correct login → PLAY, один starter1500. Matrix SHA256 `1fa4a91173690da2588575381d892deb861d67114d4addafb4a40dd82b2e1609`; result SHA256 `52ea06d2fd284a2845fe90a1938bf26dd865a0155ee856a81a1a0ff7d76fb1e1`.

Последние две проверки используют настоящие online-mode/RSA/AES/Fabric, но синтетического IDP/клиента. Это не Mojang/GUI/load-приёмка и не доказательство для нового Java JAR.

## Следующие обязательные шаги

Исправить compileTestJava, повторить точный clean test build; затем закрыть WarService queued mutations/capture authorization, execution-time bootstrap expiry и offline-owner moderation. Повторить свежие encrypted/console проверки на точном новом JAR, в том числе restart после смены startingBalance. Проверить QA-kit и CI; обновить конспект фактическими результатами.

Рабочие Paper и прежний приватный Fabric staging активны; миры/конфиги не заменялись. Рабочая копия `/opt/warland-build/release-continuation-20260906`; тяжёлые задачи только по одной под общей build.lock, VPS4GiB. Старые dirty worktrees и QA-папки сохранять, пути не переиспользовать.

Ни реальный bootstrap, ни привязка владельца, ни OP не выдавались. Никнейм не доказывает личность. Секреты, БД, миры и player data в публичный GitHub не копировать. `enableWarCapture`, buyer/AH и незавершённые покупки сохранять выключенными. Непройденные Mojang/GUI, бета10/20/30, inventory/crash/market, оставшиеся игровые этапы и Paper→Fabric/UUID миграция остаются release-блокерами.
