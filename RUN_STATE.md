# WarLand — текущая точка продолжения

Обновлено 2026-09-06, 19:33 МСК. Продолжение по прямому запросу владельца: работать над релизом автономно и сохранять состояние на GitHub. **Игровая alpha.3 уже опубликована И развёрнута. Полный stable-релиз #15 не готов.** Это сохранённая точка продолжения, не обещание фоновой работы после завершения диалога.

## Можно подключаться
- Адрес: `201.51.10.116:25565`.
- Обычный **Minecraft Java 1.21.11**. Fabric/WarLand-мод/специальный лаунчер игроку НЕ нужны.
- Регистрация/вход в нативной форме перед миром; после входа принять обязательный серверный ресурс-пак. Minecraft скачивает его автоматически. `/warland`, `/balance`, `/warland tutorial`.
- Публичный вход без whitelist, offline-account-compatible. Нужен отдельный пароль WarLand: поле НЕ маскируется; не использовать пароль почты/Microsoft и не показывать ввод посторонним. Stock client не закрепляет заранее известный ключ; native RSA/AES-CFB8 не TLS/AEAD.
- Владелец не назначается по одному нику. Reserved owner требует приватного одноразового подтверждения; секреты не публиковать.

## Фактически проверено при продолжении
- `warland-alpha.service` active/running; текущий запуск 2026-09-06 16:27:47 UTC, Minecraft ready 16:28:10 UTC, WarLand `0.1.0-alpha.3`, listener 25565. Runtime: `/opt/warland-alpha/runtime`, непривилегированный пользователь warland, приватная UNIX-консоль.
- `/opt/warland-ops/alpha3-20260906/deploy-result.json`: success=true, phase=complete, rollback_used=false, managed_files_verified=true. Идентификаторы, балансы и private server identity сохранены. Production native cancel probe: encrypted=true, dialog=true, no_mod_channels=true, compression_threshold=256, cancel accepted, до PLAY не допущен.
- Это проверка формы/барьера на production, НЕ новый полный вход реального пользователя/внешняя beta. Полный native register→pack→PLAY проверен ниже в отдельной установке. Внешнюю доступность и повторный GUI-вход ещё нужно подтвердить.
- `/opt/warland-ops/alpha3-20260906/backup-result.json`: success=true; полный private backup `/var/backups/warland-alpha3-20260906-161242`, archive verification и booted isolated restore выполнены. Clone `/opt/warland-alpha3-restore-20260906-161242`, restore_exit=0, ошибок нет; identity/database baseline сохранены. Архивы/ключи/миры/данные игроков остаются приватными.
- Production JAR SHA256 `8b769de16eb04fb775a10b033891aba100b5fa94e2e91367a8c79f85a012aff6` совпадает с опубликованным и ранее проверенным alpha.3.
- Fresh published-installer QA `/opt/warland-build/released-installer-qa-v2/result.json`: **success=true**, все пять managed downloads проверены, реальный first Fabric startup; native encrypted registration→download pack→PLAY→balance; server_exit=0, errors=[], SQLite/FK clean; synthetic starter grant один; offline resume и сохранение настроек PASS. v1 имел ERROR из-за пустого QA flat-world generator settings и НЕ считается PASS, evidence сохранено.
- Compression helper `/opt/warland-ops/alpha3-20260906/compression-result.json`: success=true, 10 codec cases, clean isolated shutdown, database unchanged. Этот helper пока существует отдельно от опубликованного QA source: нужно оформить regression tests/commit, не называть его уже включённым в tag.
- На сервере больше нет build/QA JVM; общий build.lock без держателя. Рабочая alpha JVM не останавливалась этим продолжением.

## Опубликованный релиз и прежние проверки
- https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.3 — prerelease, 6 assets.
- Реальный tag: `28cdd3a461a2c7d023f4ed5c9648c02c1a1a6d59`, ветка `release/0.1.0-alpha.3`.
- Release CI https://github.com/ProkStudio/WarLand/actions/runs/34043350094 — completed/success по предыдущему checkpoint. При новых commits проверить заново. Существующие tag/assets не перезаписывать; GitHub Immutable Releases на уровне платформы НЕ включены.
- Installer ZIP SHA256 `0df74a95639b2caed69898c397d7c3340a8b310d95a19bdf870a58ba6a0abe82`.
- Pack SHA256 `fd369c0bc4d6ebc584f4dd24ea4c057ee1e7bbbc8116b4c23c8fa884b1002488`, SHA1 `a33b73cc1a627bd73b165cf814a273edfce38bdc`, 5174 bytes.
- Exact source `522958f5f802b8e01e96f34cd8ef306149e28483`: clean build SUCCESS; Java 468 detected /464 passed /4 existing skips /0 failures/errors; Python 210/210. Native wire v3: cancel/stale/unknown/registration/pack/PLAY/balance/restart/wrong-password/login, стабильный identity, одна synthetic выплата1500, integrity/FK, без credential canaries.
- Официальный unmodified vanilla GUI ранее прошёл registration→consent→download/cache/reload→PLAY; визуально пустая русская форма, stock inventory, АК-74/магазин, `/balance1500` и меню. GUI reconnect не завершён; не подменять отдельным wire QA. Изолированная software-rendering проверка НЕ external beta/load.
- Реальный Temurin21 download/SHA256/extract/version и cache reuse PASS. Отсутствие system Java моделировалось только в поиске executable, system Java не менялась.

## Текущий следующий шаг
1. Исправить устаревшие PLAY_ALPHA/HANDOFF/ALPHA_PROGRESS/README/VANILLA_MIGRATION и server MOTD: последний всё ещё ошибочно пишет alpha.2 / Client required. Production настройки/данные сохранять; при необходимом restart сначала проверить игроков и резервное копирование.
2. Проверить доступность снаружи и реальный vanilla login/reconnect; не создавать/не менять реальный аккаунт владельца и не публиковать пароли. Для QA — отдельная synthetic среда/аккаунты, доказательства разделять.
3. Интеграция: PR40 открыт, head `5bbc419955470b16d0b47753662d09a508818279`, mergeable_state=dirty. main пока содержит только документы. Разрешить конфликты в отдельной ветке с сохранением актуальных checkpoint; проверить diff/CI; никаких force/reset чужой истории.
4. Опубликовать проверенные QA/операционные улучшения отдельным commit; не подменять immutable alpha.3 tag новым кодом.
5. Обновить #39/#12 фактическими результатами, завершить собственную reservation/LOCK после окончания jobs. Полный stable #15 и исторические CLAIM других задач не закрывать массово.

## Сохранённые пути и ограничения
- Checkout `/opt/warland-build/vanilla-release-20260906-1415`: HEAD522958f, только untracked logs/. Старый dirty `/opt/warland-build/vanilla-alpha-20260906`, stashes и `/opt/warland-build/native-wire-v2-tested-preserved.py` сохранены.
- Candidate assets `/opt/warland-build/vanilla-dist-v1/`; native evidence `/opt/warland-build/vanilla-dialog-qa-v3/result.json`; GUI `/opt/warland-build/vanilla-gui-qa-v1` и `/opt/warland-build/vanilla-gui-server-v1`; JRE `/opt/warland-build/java-bootstrap-qa-v1/result.json`.
- `tools/backup.sh` НЕ поддерживает warland-alpha.service: не обходить allowlist. Применять alpha-scoped операции с проверкой unit/WorkingDirectory, private full backup, архивной проверкой, graceful stop/start и recovery. Общие OS locks: `/opt/warland-build/build.lock`, `/var/lock/warland-backup.lock`.
- Private identity `runtime/warland/server-identity/identity.bin` mode600: не менять, не публиковать. Public fingerprint `f7c01003a649b67b97791091e687c9ee3ac275affc15dcf585a2f34df2efac8f`.
- Не удалять worlds/accounts/backups/keys/dirty worktrees. Code rollback не должен стирать действия игроков после backup. Paper/staging и другие ветки сохранять.
- Рынок/скупщик/аукцион, покупки зданий и capture отключены. Inventory crash-safety/recovery, полноценная модерация, авиация/final HUD/контент и реальные beta10/20/30 остаются незавершёнными. Не включать flags ради отметки «релиз».
