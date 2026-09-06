# WarLand — актуальная точка продолжения

**2026-09-06: alpha.3.2 опубликована И установлена. Полный stable#15 НЕ готов.** Файл сохранён по прямому запросу владельца для возобновления после обрыва. Это checkpoint, не обещание фоновой разработки после окончания чата.

## Игроку
- Сервер `201.51.10.116:25565`, обычный Minecraft Java1.21.11. Клиентские моды не требуются, ресурспак прежний и скачивается штатно.
- Теперь доступен `/rtp`: бесплатно, безопасная поверхность Верхнего мира1000–3000блоков от спавна, задержка180сек между переносами, сохраняемая после reconnect/restart.
- Во время подготовки не двигаться; бой/урон/опасность/техника/параллельное перемещение отменяют запрос. При медленной генерации возможен безопасный отказ; повторить не раньше30сек. Это ограниченная загрузка, не обещание отсутствия лагов.
- Вход пока через существующую нативную форму, НЕ через чат. Существующие аккаунты/пароли/реальный owner сохранены, повторный bootstrap не нужен. Поле пароля не маскируется; использовать отдельный пароль WarLand.

## Релиз и исходники
- https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.3.2 — prerelease,5assets: executable JAR, sources JAR, SHA256SUMS, SOURCE_COMMIT, release notes.
- Это серверный patch, НЕ новый полный installer; существующий alpha3 installer/зависимости/resource-pack не заменены.
- Фактический lightweight tag и SOURCE_COMMIT: `2c13ebad2dde95b1f0398820005e38a8e9a9414b`, ветка `release/0.1.0-alpha.3.2`. Поле GitHub release target_commitish может показывать main; проверен именно ref тега.
- Установленный executable SHA256: `ac5152842d6b1f7ac240474771333672fb2d31f4e3f4a09255b90592de429a99`.
- Release CI https://github.com/ProkStudio/WarLand/actions/runs/34051393160 — build и publish SUCCESS. Скачанные4checksums/SOURCE_COMMIT/tag/mod metadata/ZIP CRC проверены. Старые tags/assets не перезаписаны; GitHub platform immutable=false, workflow сам отказывается заменять существующий tag/release.
- PR41 merged в `agent/lobby-20260906-1722/fortress-chat-auth`, merge`ff97667a1fc3d335dd46796e2a2126bc45bdea18`. Runtime code62b1f4a; исходная ветка `agent/release-20260906-1806/rtp-integration`.
- PR42 — отдельное обновление ТОЛЬКО QA helper, head`284cccaa96346e0fb7e348ed130fea4350435634`; при последней проверке CI34051937581 ещё выполнялся. Не считать этот helper частью release tag. Проверить окончательный CI и нормально merge, если green.

## Что реализовано
Сохранена и подключена ранее подготовленная RTP реализация c8a7678/b813dd8: один глобальный запрос, ограниченные async tickets/попытки/время, устойчивый пол3×3, пространство/опасности5×5, border/collision/claims с соседними чанками, current session lease, отмена при движении/disconnect/смене мира/бое/inventory-lock/warp/war-window. В существующей SQLite state хранится180сек cooldown до переноса; денег не списывает, блоки не перестраивает. Отмена после уже принятой записи может консервативно оставить cooldown.

Исправлены3ложных test failures(Hazard fixture shadowing enum), добавлена регистрация из dedicated WarLand initializer и регрессионный тест. Опубликованный change НЕ меняет shared CoreRuntime/auth/content/HUD. Изначальный failed evidence и все старые worktrees сохранены.

## Проверки и честные границы
- Java21 clean build:513 detected/509passed/4прежних registry skips/0failures/errors; Python210/210. Exact ab67785 CI34051168112 SUCCESS18:18:38UTC;223tracked файла byte-matched.
- Предрелизный metadata3.1 JAR `0ec31a8683e1a073644da697cc1ed9128058667c0ee9018e6cba0272f2214e31` прошёл отдельные двухцикловые native auth и RTP suites. Он НЕ установлен вместо3.2.
- Скачанный опубликованный3.2 JAR прошёл fresh v2: cancel/stale/invalid auth→registration→pack/hash/CRC→PLAY/balance→успешный RTP(-1591.5,72,-2135.5)→repeat denied→graceful restart→wrong-password denied→login→persisted cooldown denied. Stable identity, одна стартовая выплата1500/balance1500, SQLite quick_check=ok/FK clean, два shutdown0, errors=[], нет password canaries.
- Первый published-JAR v1 FAILED: выбранный холодный чанк не загрузился за4сек; сервер безопасно отказал, старый positive-only harness ожидал успеха. Evidence сохранено. Новый helper распознаёт только такой отказ, проверяет отсутствие перемещения/cooldown и соблюдает30сек backoff; максимум3попытки, реальный успех всё равно обязателен. **Успешный v2 наблюдал0таких отказов, поэтому новый retry-path этим прогоном не считается покрытым.** Добавлено ожидание2.2сек после успешного переноса согласно существующему global backoff.
- Wire QA — НЕ GUI/external beta/load и НЕ полный live-negative/crash matrix. Terrain/lease negative cases: pure/SQLite/source tests. В QA наблюдались cold-start/world-load warnings7–9сек, не скрыты. Полная performance acceptance открыта.

## Подтверждённое развёртывание
- `/opt/warland-ops/rtp-alpha32-20260906/result.json`: success=true, phase=complete, deploy.exit0, rollback_used=false.
- Перед stop —0игроков, service-bound preflight, свободные common locks. `warland-alpha.service` active/running; новый запуск18:32:16UTC, WarLand ready18:32:39UTC, version0.1.0-alpha.3.2, listener25565/protocol774. Ровно1WarLand executable вmods, hashсовпадает с release.
- Private full-runtime backup `/var/backups/warland-rtp-alpha32-20260906-183205/runtime.tar.gz`, SHA256`ec14f318b03812beac6127a33cd341bc379242171ffcbaf49b852459082f970f`; tar read-back comparison выполнен. **Booted restore именно этого нового архива НЕ заявляется.** Более ранний alpha3 booted restore описан в RUN_STATE.md.
- identity_preserved=true, baseline_rows_preserved=true для profiles/auth_accounts/accounts/ledger/auth_owner, SQLite/FK clean. Production cancel-only probe увидел восстанавливаемую NONE-форму и корректную отмену, нового аккаунта не создавал. Startup errors=[]; новых build/QA JVM нет, OS build/backup locks свободны.
- Только code swap; configs/worlds/accounts/owner/keys/неоконченные flags не менялись. Старый JAR сохранён: `runtime/retired-rtp-alpha32-20260906-183205/warland-0.1.0-alpha.3.1.jar`.
- Rollback: штатно остановить ТОЛЬКО warland-alpha.service, убрать3.2изmods в отдельный сохранённый каталог, вернуть единственный прежний3.1JAR, запустить/проверить. **Не восстанавливать старую БД/миры поверх новых действий игроков.**

## Где продолжать
Own checkout `/opt/warland-build/release-20260906-1806-rtp`.
- logs/{build-v2,python-v2,runtime-v1,rtp-runtime-v1,rtp-published-v2}.exit=0; rtp-published-v1.exit=1 сохранён.
- logs/published/verified.json и исходные release assets.
- Synthetic results: `/opt/warland-build/vanilla-dialog-qa-rtp-{1806-v1,gameplay-1806-v1,published-1806-v1,published-1806-v2}/result.json` (точные существующие каталоги сверять; failed не удалять).
- Private deployment driver/evidence `/opt/warland-ops/rtp-alpha32-20260906/`. Повторно НЕ запускать уже выполненный driver: он намеренно отказывается при существующемresult.json.

Следующие приоритеты: завершить QA-only PR42; затем согласованное PLAYER_EXPERIENCE_STATUS.md — auth-chat/isolation, лобби на каждый вход, крепость160×160, TAB[OWNER], sidebar с деньгами и owner command help. RTP уже установлен, эти остальные требования НЕ считать сделанными. Main пока docs-first, PR40 конфликтный — отдельная аккуратная интеграция. Market/inventory crash-safety/purchases/capture/полная модерация/авиация/beta10/20/30/stable#15 остаются открытыми.

Не публиковать пароли/bootstrap/identity.bin/БД/миры/playerdata/архивы. Не сбрасывать реального owner и не чистить чужие dirty trees. Перед новой работой читать свежий#12, commits/PR/CI и эти checkpoints; тяжёлые jobs по одному подbuild.lock, deployment под отдельнымbackup.lock. Освобождение ресурсов отражать только после фактического окончания.
