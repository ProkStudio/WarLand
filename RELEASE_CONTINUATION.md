# WarLand — точка продолжения 2026-09-06

## Статус на21:24МСК
По прямому запросу владельца продолжены разработка и подготовка релиза. **Полный stable#15 НЕ готов.** Файл нужен для возобновления после обрыва; фоновой работы после завершения чата не обещает.

### Что выполнено
- Восстановлен доступ VPS после HTTP429.
- Сохранены исходные worktrees и локальные commits RTP. Три падения предыдущих тестов оказались конфликтом имён fixture HAZARD/enum HAZARD; исправлено без ослабления terrain policy.
- Подключён ранее не вызывавшийся RtpService из dedicated WarLand initializer. CoreRuntime/auth/content/HUD не менялись. Добавлен registration regression и новый tools/rtp_runtime_smoke.py.
- PR41 **merged** в `agent/lobby-20260906-1722/fortress-chat-auth`, merge `ff97667a1fc3d335dd46796e2a2126bc45bdea18`. Исходная проверенная ветка `agent/release-20260906-1806/rtp-integration`, head`ab677851db939568910d36e876345f2c3c244d87`.
- Java21 clean build:513 detected/509 passed/4 прежних skips/0 failures/errors. Python210/210. GitHub verify https://github.com/ProkStudio/WarLand/actions/runs/34051168112 — SUCCESS18:18:38UTC.223 tracked файла совпали с Git blobs.
- Предрелизный JAR(metadata3.1) SHA256`0ec31a8683e1a073644da697cc1ed9128058667c0ee9018e6cba0272f2214e31`.
- Native auth suite PASS: два graceful boot/stop, cancel/stale/invalid actions, registration→pack/hash/CRC→PLAY/balance, wrong-password denial→login after restart.
- Отдельный RTP runtime PASS: реальный synthetic vanilla-protocol клиент телепортирован в(2134.5,69,-1480.5); повтор отклонён; после graceful restart/login cooldown сохранился и RTP отклонён. Одна стартовая выплата1500, balance1500, stable identity, SQLite/FK clean, shutdown0/errors=[], нет canaries пароля.

### Релиз в подготовке — не путать с установленным сервером
- Ветка `release/0.1.0-alpha.3.2`, source`2c13ebad2dde95b1f0398820005e38a8e9a9414b`: Java source тот же; новая version3.2, отдельный read-only build→gated prerelease publish workflow, docs/RTP_RELEASE.md.
- CI https://github.com/ProkStudio/WarLand/actions/runs/34051393160 ещё выполнялся при этом checkpoint. Не считать готовым без проверки.
- Планируется отдельный серверный patch(JAR/sources/SHA256SUMS/SOURCE_COMMIT/notes), НЕ full installer. Старый installer alpha3 сохраняется; все старые tags/assets не перезаписываются.
- До установки обязательно скачать опубликованный3.2 JAR, сверить tag/SOURCE_COMMIT/hashes/metadata, повторить isolated RTP/auth/restart acceptance именно на нём.
- Production `warland-alpha.service` по-прежнему3.1; в этой сессии НЕ перезапускался, реальные accounts/worlds/keys/owner/flags не менялись.

## Пути и команды продолжения
Own checkout `/opt/warland-build/release-20260906-1806-rtp`; logs/{build-v2,python-v2,runtime-v1,rtp-runtime-v1}.exit все0. Не удалять logs/provisional-* и старый RTP FAILED evidence.

Synthetic evidence:
- `/opt/warland-build/vanilla-dialog-qa-rtp-1806-v1/result.json`
- `/opt/warland-build/vanilla-dialog-qa-rtp-gameplay-1806-v1/result.json`

`tools/rtp_runtime_smoke.py --jar <exact.jar> --polymer <pinned-polymer.jar> --out /opt/warland-build/vanilla-dialog-qa-<new> --port <free-loopback> --http-port <free-loopback> --synthetic-fixture`, nonroot warland-build и общий `/opt/warland-build/build.lock`. Никогда не использовать реальный аккаунт владельца/production data в QA.

Перед deployment: отдельный#12 LOCK, zero-online preflight, service-bound private full-runtime backup+verification, code-only swap, ready/version/identity/data checks, cancel-only production probe. Rollback возвращает только старый JAR, не старую БД поверх новых действий. Действующий3.1 hash`65a9627f959e5767290eda576766d98a99807097f072eb5730f1e62402b85e77`; исходный безопасный driver `/opt/warland-ops/auth-dialog-hotfix-20260906/deploy.py` нельзя запускать повторно без отдельной адаптации. Старый backup.sh не поддерживает alpha service — не обходить его allowlist.

## Незавершённое
PLAYER_EXPERIENCE_STATUS.md остаётся полным согласованным lobby/auth-chat/крепость/TAB/HUD/owner-help ТЗ. RTP реализован/проверен отдельно, остальное этой итерацией не объявляется готовым. Main всё ещё docs-first; конфликтный PR40 не слепо merge. Market/inventory crash-safety/purchase/capture/полная модерация/авиация/external beta10/20/30/stable#15 остаются открытыми.

Runtime QA — не graphical/external/load acceptance; terrain/lease негативные cases покрыты pure/SQLite/source tests, не полной live-negative matrix. Холодный QA boot/world-load давал warnings7–9сек. Source checkpoint не подменяет performance acceptance.

Не публиковать пароли/bootstrap/identity.bin/БД/миры/playerdata/private archives. Чужие ветки/worktrees/CLAIM сохранять. Перед новой сессией читать последние#12 и этот файл; освободить собственные ресурсы после фактического окончания jobs.
