# WarLand alpha.3.3 — актуальное продолжение

2026-09-06, после 22:14 МСК. **Prerelease опубликована, но production пока alpha.3.2. Не устанавливать3.3 до завершения двух exact-artifact suites.**

## Завершено
- [Релиз alpha.3.3](https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.3.3), опубликован19:11:39UTC. Это серверныйpatch, не fullinstaller/stable.
- Фактический tag/SOURCE_COMMIT: `512ec576c6496b82ad7c1e1f45d6982547bb8aa2`; executable SHA256 `9819f516697e4df1c2ef340dc97f0a343fe9cffa1682dfc00519d5013a389451`.
- CI[34053974031](https://github.com/ProkStudio/WarLand/actions/runs/34053974031):build+publishSUCCESS. Все5assetnames/sizes,4checksums,source/tag,metadata3.3,CRC,RTP/helpclasses проверены скачиванием. Platformimmutable=false; workflow отказывается заменять существующийtag/release.
- PR43 merged `cdf4a489a7f14bf07312d251777846e7348810a8` вfortress-chat-auth. Feature head1b810bf, CI34053716955SUCCESS. Java525detected/521passed/4прежнихskips/0failures/errors;Python210passed. Первый ошибочный discoverytools=0tests/exit5 сохранён; PASS только дляtools/tests.
- `/wladmin` теперь чат, `/wladmin help [1–7]`;27записей с синтаксисом, назначением, ограничениями и примерами.4записи/11сообщений максимум. Старыеrootguard/подкоманды сохранены, current-owner check повторён, отсутствующие/недоступныеузлыскрыты. Ничего не выполняет/не выдаёт/не пишет. Опасныйpurchasegate явноэкспериментальный,примерfalse.
- Предрелизный development JAR(metadata3.1) прошёл отдельный native runtime; SHA25665f073efff523338c94f955d812bbfd298adc3305a933232e676d1006fcdfb7d. Это не published3.3.
- **Точный скачанный3.3 JAR уже прошёл fresh owner-help acceptance**, help-published-v1.exit0: non-owner denial; seeded synthetic owner послеостановкисобственногоQA;restart/wrongpassword/login;7страниц/предупреждения/невернаястраница/legacydiag; owner/accounts/ledger/moderationunchanged, stablekey,однавыплата1500/баланс1500,SQLite/FKclean,двashutdown0/errors=[],нетcanaries.
- Это synthetic seeded-owner testing, НЕ enrollment/реальныйвладелец/GUI/loadприёмка. Настоящиеproductionowner/аккаунты/ключи неизменены help-этапом.

## Выполняется сейчас — проверить первым
Вторая sequential suite: RTP regression точного3.3 JAR.
- Own checkout `/opt/warland-build/owner-help-20260906-1858`, branch `agent/release-20260906-1806/owner-chat-help` at1b810bf.
- Runner PID/log/exit: `logs/published-driver.pid`, `logs/published-driver.log`, `logs/published-driver.exit`; OSbuild.lock занят только собственным finite job.
- Help result `/opt/warland-build/vanilla-dialog-qa-owner-help-published-1858-v1/result.json` PASS.
- RTP result `/opt/warland-build/vanilla-dialog-qa-help-rtp-published-1858-v1/result.json`; `logs/rtp-published-v1.{log,exit}`. **PASS ещё не наблюдался**, не предполагать успех.
- Published assets/verified.json: `logs/published/`. Не перезапускать downloader вслепую: existingdirectory намеренно блокирует повтор.

## Следующее после PASS
Свежие#12/online/service checks, отдельныйdeploymentLOCK. Новая private процедура подготовлена, но НЕ выполнялась: `/opt/warland-ops/owner-help-alpha33-20260906/deploy.py`,SHA2566d75dd83b822fd14893a9fd8b63ac721bdbedb3c35060973e566989667226c30. Жёстко требуетexact3.3hashобеихsuites и успешныйRTP/restart, прежний3.2hashac515284,0онлайн,commonbuild/backup locks. Полныйprivatebackup+tarreadback,code-onlyswap,baselineowner/key/data,ready/version/nativecancel иreadonly `/wladmin help 7` черезprivateconsole; приошибкеrollbackтолькоJAR,нестаруюБД.

Послеоперации проверитьexit/result/фактическийserver; обновитьэтотфайл,RELEASE_CONTINUATION,PLAYER_EXPERIENCE_STATUS;DONE/UNLOCKтолькопослереальногоосвобожденияресурсов. Еслиsuiteнепрошлаилионлайнигроки — оставить3.2,записатьфакт,неперезапускатьрадигалочки.

[Подробный исходный checkpoint этого этапа](https://github.com/ProkStudio/WarLand/blob/1ba8d2a4b5a77b60de359f5b11d5c0cb782f690d/OWNER_HELP_STATUS.md). Старыеdirtyworktrees/backup/source/evidenceсохранены;секреты/БД/миры/архивывGitHubнепубликовать.

Открыты: auth-chat/lobbyизоляция,lobbyкаждыйвход,крепость160×160,TAB[OWNER]/sidebarсденьгами,market/inventorycrash-safety,gameplayintegration,beta10/20/30/stable#15. Help/RTPнеозначаетихзавершение;фоновуюработупослечатанеобещать.
