# WarLand — точка автономного продолжения

Обновление 2026-09-06 после 23:28 МСК. Агент `release-20260906-2004`, задача #45, координация #12. **Production пока alpha.3.2. Диагностический RTP-прогон PASS; сейчас выполняется отдельная проверка точного опубликованного alpha.3.3. Установка ещё НЕ выполнена.** Stable #15 не готов. Предыдущие срезы доступны в истории этого файла; фоновой разработки после завершения диалога не подразумевается.

## Уже сделано
- Исправлена потеря диагностических отчётов при повторном подключении. PR46 merged `7a114ade57578b2095da128ecce498c560f76b3b` в `agent/lobby-20260906-1722/fortress-chat-auth`.
- Exact code head `7a3a0a469816d3618d0b3aecfac1a27e10b3f3ab`, CI34057365967 SUCCESS20:16:49UTC. Full Python229PASS (11новых), local9 standalonePASS и32reports/4parallelwriters/128atomicwritesPASS. Отдельные private0600reports, atomic replace/fsync, прежний latestcompatibility; лимиты/число попыток/пароли/packetbodies не менялись.
- Connector HTTP429 восстановлен после сообщения пользователя23:21. Reconciliation доказала, что прежний runtimeзапрос не выполнился; дубликатов запуска нет.
- Новая trace-only копия `/opt/warland-build/rtp-cold-20260906-2004`: Java525detected/521passed/4existing skips/0failures/errors; buildSUCCESS2m51s, JAR `a7c411ebc6a8f97519f6a61754d4d4a0bb623826489634cf1daf53309c1d6e01`.
- Этот диагностический Java patch не входит вPR46/релиз/production: включает только измерения по `-Dwarland.rtpTrace=true`; исходныеlimиты/API/heightmap/terrainpolicy не изменены. Сохранён `logs/trace-source.patch`; local author `/data/warland-2004/diagnose.py`.
- Mapped API1.21.11 подтверждаетFULL ticketradius0, nonblockinggetOrNull, правильныйheightmap+1.

## Диагностический runtime — фактический PASS
`/opt/warland-build/vanilla-dialog-qa-rtp-trace-2004-v1/result.json`, `logs/trace-runtime-v1.exit=0`.
-1CPU/640MiB, новыйempty synthetic fixture, неproduction/GUI/load.
-5загруженныхчанков:3468/2319/2302/2774/2881ms; первые16поверхностей корректно отклоненыFLOOR/HAZARD/HEADROOM; затемNONE иуспешныйRTP. Cold-loadrefusals0.
-Registration/cancel/invalidaction/auth/pack/PLAY/balance/RTP/repeatdeny/restart/wrongpassword/login/persistedcooldowndenyPASS.
-Обаshutdown0, errors=[], stableidentity,1account/1startergrant1500/balance1500, SQLite/FKclean. Startup lag7386/8939/13737ms сохранён, не скрыт. Полная пригодность поднагрузкой не доказана.
-Поэтому не стали вслепую менять ticketAPI/heightmap или ослаблятьбезопасность. Старые3coldloadfailedrun остаютсяevidence, их не заменялиPASS.

## Текущий исполняемый этап
Exact publishedalpha3.3 JAR `/opt/warland-build/owner-help-20260906-1858/logs/published/warland-0.1.0-alpha.3.3.jar`, SHA256 `9819f516697e4df1c2ef340dc97f0a343fe9cffa1682dfc00519d5013a389451`; CRC/version проверены. GitHubtag заново прочитан: `512ec576c6496b82ad7c1e1f45d6982547bb8aa2`.
-Реальныеproductionresources:2CPU/1400MiB. Новый exactgate явно2CPU/1024MiB, а НЕ прежнийstressprofile1CPU/640MiB; лимитывремени/пакетов/terrain/3attempts прежние. Не утверждать, что2CPU доказываетвоспроизводимостьна1CPU илиbeta30.
-Wrapper81473, `/opt/warland-build/rtp-cold-20260906-2004/logs/accept-published.py`, `logs/run-published.sh`, `logs/published-runtime-v1.{log,exit}`.
-Output `/opt/warland-build/vanilla-dialog-qa-rtp-published-2004-v1/result.json`; не перезапускатьвсуществующийкаталог. Сверитьresult/exit/2cycles/profile/JAR/childprocesses передновымдействием.
-Common build.lock удерживаетсяреальнымQA; послеfinish проверитьrelease. DeploymentLOCK пока не брали.

## До установки
ТолькоеслиexactgatePASS: новаяown deploymentкопия (не изменять прежнийfaileddriver), явно привязаннаякновомууспешномуRTPresult истаромуточномуowner-helpPASS. Проверить0игроков/unit/oldJARhash; privatefullbackup+archivereadback; code-onlyswap с сохранениемidentity/owner/accounts/worlds; ready/version/help/cancelpostchecks иcode-onlyrollback, неоткатБД.

Production `warland-alpha.service`, `/opt/warland-alpha/runtime`, userwarland, JAR3.2ac515284; не изменялся. Owner-help3.3 ужеpublished, ноещёненаlive. Предыдущаяполнаяистория: OWNER_HELP_STATUS.md, PLAYER_EXPERIENCE_STATUS.md, RELEASE_CONTINUATION.md; RUN_STATE.mdстарый.

Auth-chat/lobby/fortress/TAB/HUD/market+inventorycrashsafety/gameplay/load иstable#15 открыты. Не менять чужиеclaims/dirtyworktrees, не удалятьworlds/accounts/backup/evidence, не публиковатьсекреты/identity.bin/БД/миры/реальныеUUID. Сохранятьчестныерезультаты иследующийшаг, не объявлятьstableиз-заодногоPASS.
