# WarLand — автономное продолжение 2026-09-06 23:04 МСК

**Обновление около23:16МСК: исправление потери QA-отчётов опубликовано в PR46, CI ещё выполняется. Diagnostic Java clean test build SUCCESS. Новый релиз/установка НЕ выполнены; production остаётсяalpha.3.2.** Полный stable#15 не готов; после окончания диалога фоновая разработка не подразумевается.

## Актуальное состояние
- ProkStudio/WarLand, main содержит checkpoint, не актуальный игровой исходник.
- Игровая интеграция `agent/lobby-20260906-1722/fortress-chat-auth`, base `76cd43936f6ee9f369d0fc723bce20e35c5b5de1`.
- Сервис `warland-alpha`, runtime `/opt/warland-alpha/runtime`, userwarland. Последняя успешная read-only сверка: только productionJVM74932, active3.2; данные/owner/ключи/миры/flags не менялись.
- alpha.3.3 опубликована(tag512ec576,JAR9819f516); owner-help suitePASS, но установка заблокирована3cold-load RTP отказами. Полные прошлые evidence: OWNER_HELP_STATUS.md. RUN_STATE.md старее этого состояния.

## Сделано в этой итерации
- Прочитаны актуальные source, latest coordination#12, checkpoints/PRs/issues и реальное состояние VPS. Собственный агент release-20260906-2004, задача#45, ветка `agent/release-20260906-2004/rtp-cold-load`.
- Mapped Minecraft1.21.11 bytecode: radius0 ticket действительноFULL; getWorldChunk использует неблокирующий getOrNull; sampleHeightmap возвращает верхний занятыйY, существующий+1 корректен. Причина cold-load пока НЕ установлена.
- Подготовлен trace-only patch RtpService в собственном checkout `/opt/warland-build/rtp-cold-20260906-2004`: счётчики polls, load ms, max tick gap, stage/surface rejections; включается только `-Dwarland.rtpTrace=true`. Лимиты/политика неизменны. Этот Java patch пока не закоммичен/не входит в PR46/production.
- Его exact local clean test build завершёнSUCCESS/exit0 за2m51s (последующая успешная сверка). В этот момент дополнительных JVM нет, common build.lock free. JUnit totals/JAR checksum ещё не прочитаны; не подставлять старые числа.
- Исправлен подтверждённый QA defect: следующий connection перезаписывал единственныйrtp-wire-metrics.json. Новый `rtp_metrics.py` хранит отдельный private report на каждое соединение, атомарно обновляет фазы, сохраняет latestcompatibility,0600,fsync,failclosed наошибке; packet bodies/credentials не добавляет.
-9 standalone tempfile/failure-path tests локальноPASS, syntaxPASS. Ещё2 actual observed_pump integration tests опубликованы дляfullCI. Packet/time/attempt/server limits не менялись.
- Commit `7a3a0a469816d3618d0b3aecfac1a27e10b3f3ab`, PR https://github.com/ProkStudio/WarLand/pull/46 . Только4Pythonfiles. CI https://github.com/ProkStudio/WarLand/actions/runs/34057365967 — последнее наблюдениеin_progress, неPASS.

## HTTP429 / операции с неопределённым результатом
VPSconnector периодически возвращаетHTTP429. Первый tracebuild не повторялся и уже подтверждёнSUCCESS. Следующий запрос fetch4Pythonfiles + Pythonfullsuite + trace-runtime получилHTTP429 при подключении; **его исполнение пока не установлено**, слепо не повторять.

Проверить read-only перед дальнейшим запуском:
- `logs/python-metrics.exit`, `logs/python-metrics.log`;
- `logs/trace-runtime-v1.exit`, `logs/trace-runtime-v1.log`, `logs/trace-runtime-driver.log`;
- `/opt/warland-build/vanilla-dialog-qa-rtp-trace-2004-v1` иresult.json;
- собственные wrappers/JVM, common `/opt/warland-build/build.lock`, loopback25591/18101.
Если файлы/процессы отсутствуют иlockfree — тогда один новый запуск. Если работают — не дублировать; еслиfailed — сохранитьevidence/разобрать, невыдаватьPASS. Runtime helper timeout420s, Python180s; тестовый исходникemptyfixture `/opt/warland-build/release-qa/final-runtime`, неproduction. Trace build logs `logs/trace-build.{log,exit}`. Local authored `/data/warland-2004/diagnose.py` иновыеPythonfiles сохранены.

GitHub vps-build/isolated-rtp-qa reservation остаётся до безопасной reconciliation, хотя последний фактически проверенныйOSlock былсвободен. DeploymentLOCK не брали, serverrestart/swap не выполняли.

## Далее
1. Дождаться/прочитать CI PR46, проверить exacthead/base/diff, merge только QA files приPASS.
2. Reconcile VPSоперацию; собрать redacted trace cold-generation и surface rejection вновомsynthetic fixture, контролируемо сравнить1CPU/640MiB с ресурсамиVPS, без отключенияsafe bounds.
3. Причину исправить сregression; приJava изменении новаяверсия, неoverwrite3.3. PositiveRTP/repeat/restart/auth/help/datainvariants дляточногоопубликованногоJAR обязательны.
4. До установки zero-online/servicebinding/privatebackup+readback/code-onlyrollback, сохраняющий новыеplayeractions; failedgate не обходить.
5. Обновить этот файл/#12, снять только собственные locks после проверки. Не закрыватьstable#15 или чужие задачи.

Auth-chat/изолированноеlobby/крепость/TAB/HUD/market+inventorycrashsafety/gameplay/load ещёнезавершены. Секреты/identity.bin/БД/миры/реальныеUUID/архивы не публиковать. Старыеworlds/accounts/dirtyworktrees/evidence не удалять.
