# WarLand — резервный конспект агентов

**Stable-релиз и публичная альфа НЕ приняты.** Актуальные CLAIM/LOCK/решения — в [Coordination #12](https://github.com/ProkStudio/WarLand/issues/12). Этот файл не заменяет Issues, не разрешает deployment и не обещает фоновой работы. Docs-only handoff текущей002 подтверждён [#17/5559155578](https://github.com/ProkStudio/WarLand/issues/17#issuecomment-5559155578); CLAIM001 — [5559170972](https://github.com/ProkStudio/WarLand/issues/17#issuecomment-5559170972). Исторические конспекты сохранены ссылками ниже.

## Цель проекта

Полный русскоязычный военно-политический survival по README: Fabric1.21.11, обязательный клиент, до30 игроков, сохранность данных и обратимые обновления. Промежуточный alpha-план — #25. Текущая002 сообщила в #12/5559153063 о пользовательском приоритете «играбельная альфа вместо текущего сервера»; это атрибутированное сообщение другой сессии, не выполненный cutover и не готовность. Alpha и полное ТЗ#15 имеют разные критерии; нельзя объявлять весь stable завершённым по урезанной демонстрации.

## Архитектура проекта

Java21; Fabric Loader0.19.5/API0.141.6+1.21.11; Yarn1.21.11+build.6; Loom1.14.10; Gradle9.2.1. Core/auth/data/economy/nations/claims/cities/war/combat/vehicles/content/moderation/ops. SQLite WAL+synchronous FULL, один DB worker; мир/UI только server thread. Inventory и SQLite требуют durable intent/recovery, не являются одной атомарной транзакцией. Секреты, player data и runtime configs не в Git.

## Текущее состояние

- `main` — ТЗ/HANDOFF, не игровой релиз; подтверждённый SHA541072e.
- Рабочая source integration `feature/release-continuation`: `0521221d3dbca5bf8dae4a21dab530cded68759c`. Merge исходников не означает серверного обновления или проверки всего объединённого runtime.
- Source fixes #13/PR16, #14/PR18, #21/PR23, #22/PR24, #28/PR31 и #33/PR35 merged. #33 closed completed, его CLAIM освобождён.
- #29/PR36: новая successor-ветка содержит predecessor6a5d94b + normal merge0521221 + late LOGIN guard. Head `2a89fa966f0f3fabf90d785fc22aaebb52655db1`; actual CI34032630220 SUCCESS12:19:14UTC. Новый VPS build/combined runtime и независимый review ещё не подтверждены этим срезом; PR остаётся draft.
- #32/PR34: inventory planner/codec + QA tooling, не включённая торговля. #37: новая задача сохранения Paper перед будущим cutover. Публичный endpoint/реальный owner/OP/релиз001 не менял.

## Активные агенты

По прямому указанию пользователя рабочие роли только **001 и002**; старые идентификаторы — история, не дополнительные роли.

- 001: `agent-001-20260906-1445`, замена прежнего001; #29/PR36 auth и docs-only #17/AGENT_STATUS. #33/PR35 завершён. Own vps-build actual UNLOCK11:57:41UTC.
- 002: `agent-002-20260906-1516`, зарегистрировалась в #12/5559153063 как текущая002; подготовка безопасной альфы/cutover и преемство незавершённой работы002. Общие docs не редактирует, передаёт факты001.
- Предыдущая002 `agent-002-20260906-1428`: её code/evidence/PR34 сохранены. Её LOCK5559061383 (vps-build + isolated-codec-runtime,11:59:30UTC) пока не имеет подтверждённого actual UNLOCK в прочитанном срезе. Текущая002 запросила handoff/reconciliation; ожидаемый срок12:15 не снимает LOCK.

Исторические ветки/worktrees/комментарии не удалены, чужие процессы по имени не убивались. Отсутствие heartbeat не является разрешением на вмешательство.

## Активные задачи

- 001/#29/PR36: exact combined build, independent review, delayed LOGIN/CONFIGURATION native-protocol matrix. Четыре новые проверки исходного wiring не заменяют реального клиента.
- 001/#17: один согласованный19-section checkpoint; runtime remainder #17 остаётся OPEN.
- 002/#37/#25: проверяемое сохранение Paper и обратимый alpha-план; никакого переключения до реальных gates/backup+restore/rollback и release-deployment LOCK.
- #32: registry roundtrip, затем saved-player readback/guard и deposit→trade→delivery adapter. Преемство002 перед правкой CLAIM.
- #19: queued moderation/durable offline-owner safety; отдельный согласованный source scope перед продолжением. #26/PR27: новый base/full suite после #28 перед приёмкой.

## Занятые компоненты и файлы

- 001/#29: OfflineLoginMixin.java (late tickVerify) в НОВОЙ ветке, новые OfflineLoginSerializationTest.java, docs/OFFLINE_LOGIN_SERIALIZATION.md; CLAIM расширен на tools/qa/offline_login_probe.py и tools/tests/test_offline_login_probe.py (ещё подготовка, не runtime PASS). Прежний PR30/worktree не перезаписывается.
- 001/#17: AGENT_STATUS.md ONLY, branch `agent/agent-001-20260906-1445/coordination-checkpoint`, согласование5559155578.
- #32/предыдущая002: новые InventorySnapshot.java, MarketStackCodec.java, их tests/docs и codec QA harness; текущая002 координирует handoff,001 их не правит.
- Файлы #33 освобождены после merge0521221. Legacy moderation/QA branches не считать свободными/готовыми по названию; читать актуальные CLAIM/RELEASE.

## Открытые Issues

#12 Coordination; #15 stable release; #17 integrated QA; #19 moderation; #25 alpha; #26 backup/restore pipeline; #29 encrypted offline; #32 inventory codec; #37 pre-cutover Paper snapshot. Это фактически перечитанный snapshot, не вечный реестр. #13/#14/#21/#22/#28/#33 завершены; новые задачи искать перед началом работы.

## Открытые Pull Requests

#36 successor encrypted-offline/late LOGIN (draft); #34 inventory planner/codec (draft); #30 predecessor encrypted offline (draft, сохранён); #27 backup/restore pipeline; #20 исторический QA checkpoint; #11 release-continuation→auth-runtime и #10 auth-runtime→initial-release (draft). PR20 содержит устаревшие LOCK/BLOCKED формулировки, уточнённые поздними #17 comments; не сливать его вслепую поверх текущего checkpoint. Этот docs PR добавляется после публикации файла.

## Выполненные задачи

- #13/PR16: execution-time bootstrap expiry, final expiry/hash CAS, rollback и14 regressions; mergea1a2e03. Исторические RED/GREEN сведения сохранены ниже.
- #14/PR18: queued war session lease/participation и committed publication; mergee162219. Первоначальные3 failures относились к неверной owner-fixture, не к ослаблению production policy; история в исходном checkpoint.
- #21/PR23: binding release admission к installed artifact; merge2bfba0a.
- #22/PR24: backup state/recovery fail-closed; mergea58102ba.
- #28/PR31: bounded mod inventory snapshot и deterministic race regressions; merge758cbcd.
- #33/PR35: exclusive CONFIGURATION reserve до cancellation,14 regressions, independent002 COMMENT-review5125244248, actual verify34031883724 SUCCESS12:03:56UTC; normal merge0521221, #33 completed.
- 001 source COMMENT-reviews PR34/b4170b8 и PR27/b413accc опубликованы. Они не являются повторным runtime или разрешением merge устаревшей базы.

## Следующие приоритетные шаги

1. Завершить #29/PR36 на точном combined source/JAR; CI success не заменяет native delayed-wire, client GUI и independent review. Не переносить результаты predecessor на новый бинарник.
2. Текущей002 безопасно сверить процессы/evidence предыдущей002 и оформить actual handoff/UNLOCK. До этого001 не запускает Gradle/QA JVM; window REQUEST5559135121 остаётся.
3. Закончить минимальный inventory/deposit/trade/delivery с durable recovery и реальными клиентами; только codec/planner недостаточно для playable alpha.
4. Проверить Paper snapshot/restore, совместимость UUID/account/world/schema и обратимый cutover. Данные игроков/история сохраняются. Stable#15 дополнительно требует moderation/security/gameplay/load10/20/30 и всех остальных gates.

## Известные ошибки

- Early onKey check не закрывает весь delayed LOGIN. PR36 добавляет HEAD guard перед фактическим vanilla tickVerify→disconnectDuplicateLogins; delayed CONFIGURATION независимо защищён merged#33. Реальный детерминированный late-tick интервал ещё не доказан.
- Pending-first reservation может занимать UUID до120секунд: ограниченный остаточный DoS-риск, не обход пароля.
- Optional MarketFeature/реальные покупки и непроверенные inventory boundaries не готовы; не включать buyer/AH/capture ради демонстрации.
- #19 queued moderation/durable offline-owner не имеет подтверждённого исправленного PR.
- Исторические missing disabled-module/startup lag warnings сохранены; boot/idle не доказывает нагрузочную готовность.

## Технический долг

Четыре существующих PlanSafety registry/worldgen tests skipped. Deprecated Java/Gradle API, Loom remapping и SQLite semver warnings не скрывались. Некоторые HANDOFF/docs описывают уже исправленные compileTestJava/LOCK; Issues и точный SHA приоритетнее. Local computer черновики дважды исчезли после восстановления окружения; durable source/evidence в GitHub/VPS сохранены, наличие локальных файлов проверять заново.

## Состояние Minecraft-сервера

Собственная read-only проверка00111:57:41UTC: Paper minecraft.service PID11942 и исходный Fabric warland-staging.service PID31536 active, PID неизменны. Их JAR/worlds/playerDB/config/процессы001 не менял; own #33 runner/JVM завершены. Это последнее собственное наблюдение, не непрерывный мониторинг. Текущая002 отдельно сообщила read-only12:14:34UTC: Paper публичный25565 и Fabric127.0.0.1:25566 active.

Прежние001 build/smoke reconciled11:49:38UTC: сохранённые runner/build/python/smoke exit0, QA25578 не слушает, старые PID отсутствуют; native-offline result success=true. Это чтение evidence прежнего001, не новый runtime/public acceptance. Его protective LOCK явно передан через #12/5559015080.

С11:59:30UTC предыдущая002 заняла vps-build/isolated-codec-runtime для своей новой loopback25579 fixture. Actual UNLOCK пока не прочитан; не предполагать его по дедлайну/замене роли. VPS4GiB: тяжёлый job один под GitHub LOCK и `/opt/warland-build/build.lock`, nonroot/bounded heap/timeouts. Не очищать рабочие данные или старые evidence. Полный production backup001 этой source-only задачей не создавал.

## Последние значимые коммиты и ветки

Integration0521221; историческиa1a2e03/bootstrap, e162219/war,2bfba0a/artifact,a58102ba/backup,758cbcd/snapshot. #33 branch `agent/agent-001-20260906-1445/admission-reservation`, heada73e5788, сохранён. #29 successor branch `agent/agent-001-20260906-1445/offline-login-serialization`, head2a89fa9 после normal base merge34d1e003. #34 branch `agent/agent-002-20260906-1428/inventory-codec-v1`, последний прочитанный48ad1d2e (productionb4170b8 + QA tooling/tests; новые additions001 ещё не reviewed). Ни одного force push/удаления чужой ветки/изменения main001 не делал.

## Проведённые тесты

**Собственный #33 exacta73e5788:**3 baseline probes compiled/FAILED на неизменённом758cbcd; green clean test build exit0,432detected/428passed/4existing skips/0failures/errors,42XML; все14новых PASS. Python156/156 PASS.178 tracked файлов (кроме нового runbook) byte-matched опубликованному commit. JARsha256 `4b611f622c1283ef113abdcc83aa9215c2f3930205e86bd01ecf15f6a9386478`. Подробности docs/AUTH_ADMISSION_RESERVATION.md. Новый runtime этим JAR не запускался.

**PR36 exact2a89fa9:** actual CI verify34032630220/check101484925499 SUCCESS12:19:14UTC. Workflow выполняет mapped Java21 clean test build и full Python. Число тестов/XML001 пока отдельно не извлекал; не придумывать count. Local5 static boundary checks PASS,4 source-wiring JUnit добавлены. Новый VPS/runtime NOT RUN, headless/GUI/stable acceptance не заявлена.

**Предыдущая002/#32, её heartbeat12:03:57UTC:** exactb4170b8 VPS build438detected/434passed/4existing skips/0failure/error,42XML;14planner+6JSON PASS; Python156PASS. Реальная registry matrix тогда ещё ожидалась. Это атрибуция, не мой rerun и не acceptance48ad1d2e/0521221.

**История #13:**exact2419c8c RED2compiled failures, GREEN384detected/380passed/4skips,14newPASS, Python78PASS после исправления invocation PYTHONPATH; JAR `bb34df49bfd96c6bcae8f28103e4548c8daf4530c58d5bcd1e9d3be0b7b615b3`. Manifest `2c10a780352e1699c0e583f148f3f97ffe88561f8eaca189bddd94a699ee5ee6`. Base7acf914370/4skips/Python78 — отдельный старый результат.

**История #17, прежний QA-agent:**exacte162219 build418detected/414passed/4skips,Python78,private-console2cycles/exit0/no owner/OP/canary leak; JAR `ac64b404468afee949cf267744ea67c7ed4da978e0b83670f297ca4acb8d84e3`. Encrypted запуск не подтверждён. Actual UNLOCK опубликован владельцем; peer001 post-check11:00:50UTC182/182 unchanged,missing/added/changed0,symlinks0/nonemptyWAL0 (#17/5558775227), manifest `f56888ae4b7ab97ef84832b461c3fe642c8c6b1f7669445ee5513088d6fb8b9a`. Это не production backup.

**История #29:**прежний001 сообщил native encrypted-offline register→restart→wrong-password deny→login/PLAY, persistent fingerprint/один starter; позже сохранённый result прочитан как success=true. Не переносить на новый JAR,GUI,load или duplicate-race.

## Инструкции по запуску и проверке

Новый отдельный checkout точного SHA, JDK21/Python3:

```bash
python3 tools/build.py clean test build
PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v
```

При проверенном cached toolchain допустим --offline. #33 own checkout/evidence: `/opt/warland-build/agent-001-20260906-1445-admission-reservation` и `/opt/warland-build/agent-001-20260906-1445-evidence`. Checkout сохраняет base HEAD с протестированными изменениями, remotea73e5788 fetched/byte-matched; не reset/clean. Исторический #13 checkout/evidence prefix agent-stability-20260906-122149 сохранён; не смешивать dirty worktrees.

Runtime только после чтения docs/OPERATIONS.md, PRIVATE_CONSOLE.md и guardrails harness, fresh own output/nonroot/loopback/GitHub+OS LOCK. Никаких synthetic IDP overrides на рабочем сервере. Не печатать secrets/playerDB/config. Native-offline QA требует заранее известного synthetic public-key pin, не TOFU.

## Инструкции по откату

Source-only публикация не является deployment. Сейчас безопасно не устанавливать unaccepted JAR; source revert обычным commit без переписывания истории. Старые JAR возвращают исправленные security defects; не ослаблять CONFIGURATION reservation/encryption/pin/auth. При offline-specific проблеме оставить rollout закрытым или вернуться к reviewed online-only setup. QA останавливать только по подтверждённым собственным PID, evidence сохранять. Будущее обновление требует проверенного backup/restore, schema/UUID compatibility и сохранения новых действий игроков; опасные/необратимые действия требуют отдельного согласия. Не удалять worlds/player data/accounts/audit/backups/чужие ветки.

## Инструкции для следующего агента

Начать с #12 и последних #17/#19/#25/#29/#32/#37 comments, PR/head/base/CI/веток/commits и свежего AGENT_STATUS. Продолжать только назначенную роль001 или002, не создавать третью. Перед shared writes/restart/merge перечитать CLAIM/LOCK. Нет фактического UNLOCK — ресурс занят; пользовательская смена роли не отменяет необходимость проверить процессы. После шага сохранить точные результаты/ограничения/DONE/RELEASE/actualUNLOCK. Не выдавать старое evidence за новый source/JAR.

История не потеряна: [исходный19-section срез09:48](https://github.com/ProkStudio/WarLand/blob/758cbcd17b6a5055fbd6b73e078ef8bb564245ac/AGENT_STATUS.md), [QA checkpoint PR20](https://github.com/ProkStudio/WarLand/blob/26c70ab2baad8f97c5c06260cc75a08cf0f0ccac/AGENT_STATUS.md), [полный отчёт прежнего QA](https://github.com/ProkStudio/WarLand/blob/26c70ab2baad8f97c5c06260cc75a08cf0f0ccac/docs/INTEGRATED_AUTH_WAR_QA.md). Их поздние corrections/UNLOCK в Issues имеют приоритет. Не merge старый PR20 поверх текущих фактов без нового review.

## Дата последнего обновления

2026-09-06, checkpoint текущей001 около12:21UTC (15:21 Europe/Moscow): integration0521221/#35 completed, PR36 exact2a89fa9 CI SUCCESS12:19:14UTC; текущая0021516/docs handoff подтверждены. Server/build LOCK предыдущей002 ещё ждёт actual reconciliation/UNLOCK. Это docs-only срез, результаты следующего runtime/cutover сюда не приписаны заранее.
