# WarLand — резервный конспект агентов

**Стабильный релиз НЕ готов.** Главный канал — [Coordination #12](https://github.com/ProkStudio/WarLand/issues/12), затем актуальные Issues/PR/checks. Этот файл — резервный snapshot, не замена CLAIM/LOCK. Checkpoint: `2026-09-06T10:40:56Z`, автор `agent-stability-20260906-122149`.

## Цель проекта

Полный русскоязычный военно-политический survival по README: Fabric1.21.11, обязательный клиент, до30 игроков, сохранность данных и проверенный откат. Успешная alpha-сборка и синтетический вход не подменяют готовый стабильный релиз.

## Архитектура проекта

Java21, Fabric Loader0.19.5/API0.141.6+1.21.11, Yarn1.21.11+build.6, Loom1.14.10, Gradle9.2.1. Core/auth/data/economy/nations/claims/cities/war/combat/vehicles/content/moderation/ops. SQLite WAL+synchronous FULL, один DB worker; мир/UI — серверный поток. Секреты и runtime data не хранятся в Git.

## Текущее состояние

- `main` остаётся ТЗ/HANDOFF, не игровым релизом. Рабочая интеграция — `feature/release-continuation` / **e162219e4d137e2828fd04731427164fc33019fe**.
- #13/PR #16 (bootstrap expiry) и #14/PR #18 (war session authorization) завершены и последовательно merged после взаимного review и зелёного CI. Это source integration, не deployment.
- #17: общий clean build/Python и fresh private-console2 cycles прошли. Encrypted auth/restart и финальная постпроверка **не подтверждены**: запрос запуска и последующие read-only обращения к VPS получили HTTP429. Не повторять запуск без проверки состояния.
- #19: второй агент готовит moderation/offline-owner fix в отдельной ветке. Он не входит в проверенный e162219/JAR.
- Полный release checklist #15 открыт; release-candidate/freeze/deployment не объявлены.

## Активные агенты

- `agent-stability-20260906-122149`: #17, ветка `agent/agent-stability-20260906-122149/integrated-auth-war-qa`; QA evidence/checkpoint. Собственный vps-build LOCK пока не снят из-за неопределённого результата обращения при запуске encrypted fixture; тяжёлая операция не дублируется.
- `agent-stability-20260906-122256`: #14 завершена/DONE, теперь #19, ветка `agent/agent-stability-20260906-122256/moderation-authorization`; разрешена лёгкая подготовка. Новый Gradle/JVM только после actual UNLOCK #17 и своего LOCK. Проверенных результатов #19 пока не получено.

## Активные задачи

- #17: exact e162219 build/console PASS; encrypted/post-check BLOCKED. Запрошена независимая лёгкая сверка PID/exit/output state у второго агента, без запуска/остановки чужих процессов.
- #19: captured session + durable role/owner guard, offline owner targets, stale moderation state и корректная committed publication.
- #15: полная релизная приёмка; широкие функциональные и эксплуатационные gates открыты.

## Занятые компоненты и файлы

- #17/agent-stability-20260906-122149: `docs/INTEGRATED_AUTH_WAR_QA.md`, `AGENT_STATUS.md`, собственные runner/evidence/новые QA-пути. Production/test исходники не редактируются.
- #19/agent-stability-20260906-122256: `src/main/java/ru/warland/moderation/Moderation.java`, новый `ModerationRepository.java`, совместимое расширение `ModerationPublication.java`; новые `ModerationLeaseTest.java`, `ModerationAuthorizationWiringTest.java`, `docs/MODERATION_SESSION_AUTHORIZATION.md`.
- #19 не трогает AuthRuntime/AuthRepository, CoreRuntime, Store, AuthModerationMixin, схемы, flags и этот AGENT_STATUS. #13/#14 scopes освобождены через DONE, но перед новой задачей перечитать #12.
- Старые branches/dirty trees не считать свободными/готовыми по названию. Не редактировать чужие компоненты без согласования.

## Открытые Issues

[#12 Coordination](https://github.com/ProkStudio/WarLand/issues/12), [#15 stable release](https://github.com/ProkStudio/WarLand/issues/15), [#17 integrated QA](https://github.com/ProkStudio/WarLand/issues/17), [#19 moderation](https://github.com/ProkStudio/WarLand/issues/19). #13 и #14 closed/completed; это не закрытие всего релиза.

## Открытые Pull Requests

- [#11 release continuation](https://github.com/ProkStudio/WarLand/pull/11): draft → feature/auth-runtime, head e162219.
- [#10 auth runtime](https://github.com/ProkStudio/WarLand/pull/10): draft → development/initial-release.
- #16/#18 merged. Этот QA checkpoint публикуется отдельным документационным PR, ссылка фиксируется в #17/#12. Перед работой обновить список PR; snapshot не является вечным реестром.

## Выполненные задачи

- #13: реальное воспроизведение stale-time defect, execution-time clock, строгий финальный expiry/hash CAS, atomic rollback и14 регрессий; PR #16 merged как a1a2e03.
- #14: guard4 queued mutations, фильтрация authorized capture/contest/online участников, независимая от observer cancellation публикация committed cache,34 новые проверки; PR #18 merged как e162219.
- Взаимные независимые agent-ID COMMENT reviews без блокеров; оба агента используют один GitHub account, поэтому не имитировали formal APPROVE и не обходили checks.
- #17: новый общий170-file checkout и build418/Python78; fresh private-console/bootstrap2 cycles подтверждены. Не выдавать эти выполненные части за завершение всего #17.

## Следующие приоритетные шаги

1. Восстановить read-only доступ VPS и сверить `encrypted-auth-runner.pid/.exit`, output path, только собственные QA PID и common build.lock. После HTTP429 не делать слепой повтор side effect. Если запуск не состоялся, явно передать/освободить ресурс либо согласовать новый запуск; если есть свой процесс — дождаться/штатно завершить только его.
2. Закончить оставшийся #17: encrypted fixture с synthetic IDP, post-check182 постоянных файлов исходной fixture, состояния служб/портов/процессов и actual UNLOCK. Нет отчёта/timeout/ошибка — не PASS.
3. #19 продолжает его владелец; после реальных тестов/CI — независимое review. Новый код потребует нового общего SHA/build/runtime, старый e162219 evidence не переносится автоматически.
4. По #15: реальные Mojang/companion UI, inventory/escrow/market/crash boundaries, войны/остальной gameplay, beta10/20/30, backup/restore и Paper→Fabric/UUID migration. До этого stable release отсутствует.

## Известные ошибки

- Неустранённые moderation queued authority/offline durable-owner/stale-state gaps — #19, не считать исправленными до проверенного PR.
- Отсутствует optional `ru.warland.economy.MarketFeature`; безопасные buyer/AH/inventory boundaries не завершены. Включать flags ради демонстрации нельзя.
- В двух новых private-console стартах зафиксирован startup lag6360ms/127ticks и6101ms/122ticks; это не признание приемлемой производительности. Real load не проверен.
- Подключение VPS возвращает HTTP429: факт старта encrypted QA после ошибки не установлен. Это ограничение наблюдаемости/исполнения, а не доказанный дефект игрового кода или успешный тест.

## Технический долг

Четыре PlanSafetyTest с настоящими registry/worldgen codec остаются skipped. Deprecated Java/Gradle API и Loom remapping warnings сохранены. Старые HANDOFF/auth docs могут описывать уже исправленные compile errors или неинтегрированный runtime; приоритет — exact SHA/Issues. Полное ТЗ и disaster recovery рабочих данных не закрываются unit/синтетическими тестами.

## Состояние Minecraft-сервера

Последняя подтверждённая read-only проверка исходных служб10:21:37UTC: `minecraft.service` Paper (PID11942) и `warland-staging.service` Fabric (PID31536) active. Их JAR/config/worlds/процессы нашими действиями не изменялись и не перезапускались; реальный owner/OP не создавался. После HTTP429 текущий health повторно не подтверждён.

Новый private-console QA на127.0.0.1:25576 завершил оба цикла штатно, runner46373 exit0. Возможный encrypted QA на25577 требует сверки после ошибочного обращения; не предполагать ни запуск, ни отсутствие процесса. Common vps-build GitHub LOCK принадлежит agent-stability-20260906-122149, публикация в #12; ожидаемое10:35UTC не является освобождением. Снимать только после reconciliation, не обходить чужим build.

VPS4GiB: один тяжёлый Gradle/JVM/runtime под общей `/opt/warland-build/build.lock` и GitHub LOCK, один worker и ограниченный heap. Production backup в этой недеплойной сессии не создавался; старые backups сохранялись. Перед будущим deployment нужны актуальный backup и фактическая проверка восстановления.

## Последние значимые коммиты и ветки

- Base7acf914: наследованная integration после starter/nation lease и исправления compile fixture.
- #13 code2419c8c, docs0efa23b, merge a1a2e03; ветка agent/agent-stability-20260906-122149/bootstrap-expiry сохранена.
- #14 code92de7c7, tests/docs6258833, общий merge **e162219**; ветка agent/agent-stability-20260906-122256/war-session-authorization сохранена.
- #17 QA branch от e162219; #19 moderation branch отдельно от e162219. Никакого force-push, удаления веток или изменений main.
- Legacy fix/moderation-auth-continuation указывала на ef475977 без нового опубликованного fix; её не переписывать/cherry-pick как готовую только по названию.

## Проведённые тесты

**Exact e162219 / #17:** clean test build exit0; JUnit418 detected/414 passed/4 existing skips/0 failures/errors,40 XML, bootstrap14 +war29 +wiring5 все присутствуют и проходят; Python78/78. Все170 tracked файлов byte-matched. Pinned Gradle ZIP и extracted distribution сверены, diff --check passed.

Executable JAR SHA256 `ac64b404468afee949cf267744ea67c7ed4da978e0b83670f297ca4acb8d84e3`. Integrated GitHub verify success: runs34026569755 и34026567502. Старые #13 Java384/Python78 и #14 Java404/Python78 сохранены отдельно, не подменяют общий418-run. Ошибочная первая Python invocation #13 и failed owner-fixture #14 честно сохранены/исправлены без ослабления production policy.

Fresh private-console/bootstrap2 cycles: status/bootstrap/private modes/неперезапись proof/no log canary/no owner/OP,0 persistent player/economy records, quick_check=ok/fk_errors0, оба exit0/errors0. Warnings выше. Encrypted auth/restart и финальная post-check **НЕ ПОДТВЕРЖДЕНЫ**. Подробнее — docs/INTEGRATED_AUTH_WAR_QA.md и #17.

## Инструкции по запуску и проверке

JDK21/Python3, новая своя копия точного source SHA:

```bash
python3 tools/build.py clean test build
PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v
```

При проверенном cache допустим --offline. Новый #17 checkout: `/opt/warland-build/agent-stability-20260906-122149-integrated-auth-war-qa`; evidence: `/opt/warland-build/agent-stability-20260906-122149-integrated-evidence/`. Старый bootstrap checkout с base HEAD/dirty source и чужие worktrees не чистить.

Runtime — только прочитанные docs/OPERATIONS.md, docs/PRIVATE_CONSOLE.md и guardrails соответствующих tools, fresh output/loopback/непривилегированный user под LOCK. Сначала reconcile возможный encrypted запуск. Существующие QA/evidence paths не переиспользовать и не очищать. Не печатать proof/password/config/DB contents в чат или Git.

## Инструкции по откату

Новый JAR не развёрнут на рабочие службы; сейчас откат — не устанавливать его. Для QA штатно завершать только собственные процессы, сохранять evidence. Миграций схемы #13/#14 нет; возврат старого JAR возвращает security defects. Не удалять accounts/audit/worlds/player data/backups и не откатывать чужие изменения. Будущий production restart требует отдельного согласования, актуального backup/restore, сохранения новых действий игроков и проверенной schema compatibility.

## Инструкции для следующего агента

Сначала свежие #12/#17/#19, PR/checks/ветки/коммиты и текущий AGENT_STATUS. Свой уникальный ID и отдельный Issue/branch/CLAIM; отсутствие DONE/RELEASE означает занятую область. Не путать pending result с PASS. При HTTP429 соблюдать паузу и проверять состояние после неоднозначного side effect перед повтором; не искать/копировать secrets и не обходить ограничения подключения. Перед shared action подтвердить фактические LOCK, не полагаться на ожидаемый срок. Checkpoint обновлять целевым изменением, сохраняя чужие записи; Issues/комментарии приоритетнее snapshot.

## Дата последнего обновления

`2026-09-06T10:40:56Z`, agent-stability-20260906-122149. Частичный QA checkpoint: build/console доказаны, encrypted/post-check заблокированы и требуют продолжения. Стабильный релиз и завершение #17 не заявлены.
