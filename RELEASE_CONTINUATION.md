# WarLand — актуальная точка продолжения

**2026-09-06: alpha.3.2 опубликована И установлена. Полный stable#15 НЕ готов.** Конспект сохранён по запросу владельца для возобновления после обрыва, не означает фоновую разработку после окончания чата.

[Полный неизменяемый снимок этого этапа: тесты, ошибки, пути, ограничения и rollback](https://github.com/ProkStudio/WarLand/blob/ccd7c93887eeafca42df427928b75cf5e8578546/RELEASE_CONTINUATION.md). Ниже — окончательный статус; в подробном снимке PR42 ещё ожидалCI, теперь он тожеmerged.

## Игроку
- `201.51.10.116:25565`, обычный Minecraft Java1.21.11, клиентские моды не нужны, прежний ресурспак скачивается штатно.
- `/rtp` теперь работает: бесплатно, безопасная суша Верхнего мира1000–3000блоков от спавна,180сек между переносами, cooldown сохраняется послеreconnect/restart. Не двигаться во время подготовки; бой/урон/опасность/техника/inventory-lock/параллельныйwarp отменяют запрос.
- Холодный чанк может не загрузиться за4сек: безопасная отмена, повторять не раньше30сек. Ограничение предотвращает накопление работы, но не гарантирует отсутствие лагов.
- Авторизация пока прежней native-формой, НЕ чатом. Реальные аккаунты/пароли/owner сохранены; повторныйbootstrap не нужен. Поле пароля не маскируется — использовать отдельный парольWarLand.

## Релиз и интеграция
- [v0.1.0-alpha.3.2](https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.3.2): prerelease,5assets(executable,sources,SHA256SUMS,SOURCE_COMMIT,notes). Это серверныйpatch, не новыйfull installer.
- Фактический lightweight tag/SOURCE_COMMIT `2c13ebad2dde95b1f0398820005e38a8e9a9414b`, ветка `release/0.1.0-alpha.3.2`. Release metadata target_commitish может показыватьmain; проверять именноtag ref.
- Установленный executable SHA256 `ac5152842d6b1f7ac240474771333672fb2d31f4e3f4a09255b90592de429a99`.
- Release CI[34051393160](https://github.com/ProkStudio/WarLand/actions/runs/34051393160): build+publish SUCCESS. Скачанныеchecksums/source/tag/version/CRC сверены. Старыеtags/assets не заменены; platform immutable=false, workflow сам отказывается отoverwrite.
- [PR41](https://github.com/ProkStudio/WarLand/pull/41) merged как`ff97667a1fc3d335dd46796e2a2126bc45bdea18`: RTP реализация+startup wiring+tests.
- [PR42](https://github.com/ProkStudio/WarLand/pull/42) merged как`d0fbfeabf60957db222f9874fbf05694993fed20`: QA-only helper. Exact helper284ccca CI[34051937581](https://github.com/ProkStudio/WarLand/actions/runs/34051937581) SUCCESS18:33:28UTC. Это НЕ изменение опубликованногоJAR/tag.
- Свежая dev-интеграция: `agent/lobby-20260906-1722/fortress-chat-auth` at`d0fbfeabf60957db222f9874fbf05694993fed20`. Main по-прежнемуdocs-first; PR40 конфликтный — не делатьслепойmerge.

## Доказательства
- Java21 clean build:513detected/509passed/4прежнихregistry skips/0failures/errors; Python210/210. Предрелизныйexactab67785 CI34051168112 SUCCESS;223trackedfiles byte-matched.
- Настоящие isolated Fabric/native vanilla-wire suites: cancel/stale/invalid authentication, registration→pack/hash/CRC→PLAY/balance, успешныйRTP→repeat denial→graceful restart→wrong-password denial→login→persisted-cooldown denial. Стабильныйключ, однастартоваявыплата1500/balance1500,SQLite/FK clean,shutdown0/errors=[],безpasswordcanaries.
- Exact downloaded3.2artifact v2 PASS: landing(-1591.5,72,-2135.5),repeat/restartcooldowndenied. Первыйpublishedv1 FAILED из-за предусмотренногоcold-loadtimeout; evidenceнеудалён. Новыйhelper распознаёт только этототказ, проверяетno position/cooldown change,ждёт30сек,максимум3попытки и всёравно требуетреальныйуспех. **Успешныйv2 наблюдал0отказов: retry-path им НЕ покрыт.**
- WireQA НЕGUI/externalbeta/load/полныйlive-negative/crashmatrix. Terrain/lease negatives — pure/SQLite/source tests. Cold-start/world-load warnings7–9сексохранены. Дополнительныеподробности/старыеfailedruns — вснимке выше.

## Production и откат
- `/opt/warland-ops/rtp-alpha32-20260906/result.json`:success=true/phasecomplete/deploy.exit0/rollback_used=false.
- Доrestart0игроков; service-boundpreflight. `warland-alpha.service`active/running,запуск18:32:16UTC,ready18:32:39UTC,version3.2,protocol774,ровно1WarLandexecutable/hashсовпадает.
- Privatebackup `/var/backups/warland-rtp-alpha32-20260906-183205/runtime.tar.gz`,SHA256`ec14f318b03812beac6127a33cd341bc379242171ffcbaf49b852459082f970f`;tarread-backcomparisonPASS. **BootedrestoreименноэтогоархиваНЕзаявляется**;раннийalpha3bootedrestoreописанвRUN_STATE.md.
- identity_preserved/baseline_rows_preserved=true(profiles/auth_accounts/accounts/ledger/auth_owner),SQLite/FKclean. Productioncancel-onlyprobe:NONEdialog/cancelPASS,безсозданияаккаунта.Startuperrors=[];QA/buildJVMнет,commonOSlocksсвободны;DONE/UNLOCKв#12опубликован.
- Толькоcode-swap;config/worlds/accounts/owner/keys/незавершённыеflagsнеизменялись.СтарыйJAR:`runtime/retired-rtp-alpha32-20260906-183205/warland-0.1.0-alpha.3.1.jar`.
- Rollback:gracefulstopтолькоэтогосервиса,сохранить3.2внемods,вернутьединственныйстарый3.1JAR,start+проверки. **НевосстанавливатьстаруюБД/миры поверхновыхдействийигроков.** Повторновыполненныйdeploy.pyне запускать:existingresult.jsonнамеренноблокируетповтор.

## Следующие шаги
1. Следовать[PLAYER_EXPERIENCE_STATUS.md](PLAYER_EXPERIENCE_STATUS.md):auth-chatсизоляциейдоproof,lobbyнакаждыйвход,крепость160×160,TAB[OWNER],sidebarсденьгами,ownercommandhelp. **RTPсделан;остальныепунктыэтойитерациейНЕсделаны.**
2. Отдельнаяаккуратнаяинтеграцияsourceвmainссохранениемcheckpoint,непереписываяисторию.
3. Дальшеinventory/marketcrash-safety,purchases/capture,полнаямодерация/авиация,реальныеbeta10/20/30иstable#15. Невключатьопасныеflagsради«релиза».

Owncheckout `/opt/warland-build/release-20260906-1806-rtp`;logs/{build-v2,python-v2,runtime-v1,rtp-runtime-v1,rtp-published-v2}.exit=0;rtp-published-v1.exit=1сохранён;logs/published/verified.jsonиreleaseassetsнаместе. Точныеprivateevidencepaths—вподробномснимке. Старыеdirtyworktrees/архивы/ключинечистить. Передпродолжениемчитатьсвежий#12/PR/CI,новаязадачавотдельнойкопии,одинheavyjobподbuild.lock;deploymentподbackup.lock. Пароли/bootstrap/identity.bin/БД/миры/playerdata/архивывGitHubнепубликовать.
