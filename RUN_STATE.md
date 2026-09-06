# WarLand — текущая точка продолжения

Обновлено 2026-09-06, около 19:02 МСК. Задача #39: обычный Minecraft Java 1.21.11 и автоматические загрузки. Пользователь просит продолжать автономно до релиза и сохранять состояние здесь. Это ограниченная alpha.3, не полный stable/#15.

## Релиз уже опубликован
- https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.3 — prerelease, все 6 assets uploaded.
- Реальный tag указывает на `28cdd3a461a2c7d023f4ed5c9648c02c1a1a6d59`, ветка `release/0.1.0-alpha.3`. Поле target_commitish в release metadata не заменяет проверку tag.
- Release CI https://github.com/ProkStudio/WarLand/actions/runs/34043350094 — completed/success. Код публикации не перезаписывает существующие tag/release; GitHub Immutable Releases на уровне платформы НЕ включены.
- JAR SHA256 `8b769de16eb04fb775a10b033891aba100b5fa94e2e91367a8c79f85a012aff6`: CI artifact совпадает с локальным/GUI-проверенным.
- Installer ZIP SHA256 `0df74a95639b2caed69898c397d7c3340a8b310d95a19bdf870a58ba6a0abe82`.
- Pack SHA256 `fd369c0bc4d6ebc584f4dd24ea4c057ee1e7bbbc8116b4c23c8fa884b1002488`, SHA1 `a33b73cc1a627bd73b165cf814a273edfce38bdc`, 5174 bytes.
- PR https://github.com/ProkStudio/WarLand/pull/40 открыт, не merged: feature head `5bbc419955470b16d0b47753662d09a508818279`. Его CI success относится к feature branch; publish там штатно skipped. Release branch имеет дополнительный QA-doc commit.

## Проверки завершены
- Exact source `522958f5f802b8e01e96f34cd8ef306149e28483`: clean Gradle build SUCCESS; Java468 detected /464passed /4existing skips /0failures/errors; Python210/210. Реальный package_server.py собрал все assets, SHA256SUMS проверены.
- Native wire v3 PASS: cancel с точной причиной; stale/unknown actions; native encrypted transport без mod channels; registration; auth до pack/PLAY; реальный pack hash/CRC; balance; graceful restart; wrong password; повторный login; одна синтетическая стартовая выплата1500; стабильный identity; integrity/FK; без credential canaries в логах. Старый v1 failed из-за harness NBT length-prefix; исправлено, опубликовано и повторно проверено.
- Официальный unmodified Minecraft Java1.21.11 main net.minecraft.client.main.Main, без Fabric/WarLand клиента: native registration, штатное согласие, скачивание/cache/reload pack, PLAY. Визуально просмотрены пустая русская форма, stock inventory и модель АК-74/магазина без missing texture. Реальный GUI /balance1500 и /warland menu. GUI reconnect не завершён; неверный пароль/restart покрыты отдельным native wire, не GUI.
- GUI supervisor закончен: client143 при ограниченном завершении, server0, Xvfb0, без forced kill. Это loopback/software-rendering QA, не внешняя beta/performance проверка. Секретные synthetic password files и приватные runtime/screenshots не публиковать.
- Реальный Temurin Java21 download/SHA256/extract/version и повторное использование PASS. Отсутствие system Java моделировалось только в поиске executable; системная Java не менялась.

## Точный незавершённый этап
1. Сейчас выполняется fresh installation из опубликованного checksum-verified ZIP: `/opt/warland-build/released-installer-qa-v1/probe.py`, driver63808; проверять `result.json`, `exit`, `probe.log`, `server.log`. Нельзя считать PASS до чтения итогового результата. Загрузка установщика и пяти managed dependencies уже завершилась; first Fabric startup ещё проверяется. QA-only loopback25582, flat world и отключённая compression для wire helper; никакого production data в этом runtime.
2. Production по последней проверке всё ещё alpha.2 active; данная сессия его не меняла. Нужны приватный полный backup, проверка архивов и booted isolated restore, затем service-specific alpha.3 cutover/rollback с сохранением данных и private identity.
3. Обновить PLAY_ALPHA/HANDOFF/ALPHA_PROGRESS/README после фактического deployment. Проверить main/PR40 перед merge; не делать force/reset. Снять свой #12 LOCK после остановки собственных работ.

## Где искать состояние и что сохранять
- VPS checkout `/opt/warland-build/vanilla-release-20260906-1415`: HEAD522958f; более поздние feature/release commits добавляют только workflow/docs. Untracked logs/ не публиковать. Passed artifacts `/opt/warland-build/vanilla-dist-v1/`.
- `/opt/warland-build/vanilla-release-build-v3.exit` =0; driver/java/python/package/smoke logs рядом; `/opt/warland-build/vanilla-dialog-qa-v3/result.json` success=true. v1/v2 evidence сохранить.
- GUI `/opt/warland-build/vanilla-gui-qa-v1` и `/opt/warland-build/vanilla-gui-server-v1`; Java fallback `/opt/warland-build/java-bootstrap-qa-v1/result.json`.
- Исходный dirty checkout `/opt/warland-build/vanilla-alpha-20260906` не тронут. Сохранены initial patch, stashes `preserved-composite-before-exact-source`, `native-wire-v2-tested-before-published-sync` и `/opt/warland-build/native-wire-v2-tested-preserved.py`.
- Координация/cutover reservation: https://github.com/ProkStudio/WarLand/issues/12#issuecomment-5560298341 . Тяжёлые работы под `/opt/warland-build/build.lock`, backup дополнительно `/var/lock/warland-backup.lock`. Paper/staging/другие worktrees не трогать.

## Production и обязательные ограничения следующего шага
- `warland-alpha.service`, `/opt/warland-alpha/{app,runtime}`, user/group warland, private console `/run/warland-alpha/console.sock`, endpoint `201.51.10.116:25565`.
- Последний baseline: online0; profiles/auth_accounts/accounts/ledger =1/1/1/1; integrity ok/FK0. Поля starter_grants/starter_total helper считают его synthetic UUID, на production равны0/0: НЕ применять ожидание QA1/1500 к реальным игрокам.
- Реальный private identity: `runtime/warland/server-identity/identity.bin`, mode600. Старый запрос runtime/warland/private-auth проверял другой путь, а не отсутствие identity. Ключ не публиковать/не менять. Публичный fingerprint `f7c01003a649b67b97791091e687c9ee3ac275affc15dcf585a2f34df2efac8f`.
- Runtime без symlinks/special files по preflight; свободно32GiB. Unit WorkingDirectory/User/Group/FragmentPath подтверждены, drop-ins отсутствовали. Перед maintenance перепроверить.
- tools/backup.sh ALLOWLIST НЕ включает warland-alpha.service. Не вызывать его под ложным именем сервиса. Нужна строго alpha-scoped процедура с проверкой WorkingDirectory/состояния, locks, graceful stop, archive verify и recovery при ошибке.
- tools/restore_snapshot.py ожидает gzip tar с runtime файлами в корне (server.properties, fabric-server-launch.jar, warland/warland.db), новый destination, известный SHA256; отвергает links/devices/sparse/unsafe paths, проверяет SQLite/FK. Полный installation archive с префиксом opt/... ему не подходит. Не применять --sparse.
- Приватный restored production runtime оставлять root/warland-only, НЕ отдавать warland-build. Не подменять backup/restore проверкой synthetic fixture. Откат кода не должен стирать появившиеся после backup данные.

## Границы alpha
Пароль native form виден; нужен отдельный пароль WarLand, не от почты/Microsoft, ввод не показывать посторонним. Stock client не pin-ит заранее известный ключ; RSA/AES-CFB8 не TLS/AEAD. Вход публичный offline-account-compatible без whitelist. egorkrid666 получает owner только через приватное подтверждение, никогда по одному нику; старый proof истёк, новый не публиковать.

Market/crash-safety, покупки/захваты, авиация/final HUD, полная moderation/recovery и beta10/20/30 остаются незавершёнными; flags не включать ради релиза. Старые worlds/backups/keys/dirty trees сохранять. После завершения диалога работа автоматически не продолжается.
