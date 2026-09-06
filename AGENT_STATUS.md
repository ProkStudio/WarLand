# WarLand — резервный конспект агентов

**Стабильный релиз НЕ готов.** Актуальный канал — [Issues и комментарии #12](https://github.com/ProkStudio/WarLand/issues/12); этот файл не заменяет CLAIM/LOCK. Первый конспект создан `agent-stability-20260906-122149` по согласованию со вторым агентом. Срез сведений: 2026-09-06T09:48:31Z.

## Цель проекта

Полноценный русскоязычный военно-политический survival WarLand по README: Fabric 1.21.11, обязательный клиент, до30 игроков, сохранность данных и проверенный откат. Не подменять полный релиз ограниченной alpha или успешной компиляцией.

## Архитектура проекта

Java21, Fabric Loader0.19.5/API0.141.6+1.21.11, Yarn1.21.11+build.6, Loom1.14.10, Gradle9.2.1. Основной мод: core/auth/data/economy/nations/claims/cities/war/combat/vehicles/content/moderation/ops. SQLite WAL+synchronous FULL, один DB worker; UI/мир — серверный поток. Секреты и runtime data не в Git.

## Текущее состояние

- `main` содержит ТЗ и HANDOFF, не готовый игровой сервер.
- Рабочая интеграция: `feature/release-continuation` / `7acf914`, draft PR #11 → `feature/auth-runtime`; draft PR #10 → `development/initial-release`.
- Bootstrap fix опубликован как `2419c8c34ea4c70a0819a0a0341e2d121f05f506`, PR #16 → рабочая интеграция; проверен, но ещё не merged/deployed.
- Широкие release gates открыты в [#15](https://github.com/ProkStudio/WarLand/issues/15). Наличие release checklist не означает freeze или разрешение deployment.

## Активные агенты

- `agent-stability-20260906-122149`: #13 / PR #16, bootstrap expiry; публикация документации/checkpoint и review. Свой vps-build LOCK снят в09:41:15UTC.
- `agent-stability-20260906-122256`: #14, branch `agent/agent-stability-20260906-122256/war-session-authorization`; war queue/participation authorization. По сообщению в #12 на09:48UTC причина3 failures установлена в owner-fixture и исправлена тестом реального делегата; повторная полная сборка ещё ожидает результата. Его vps-build LOCK от09:42:25UTC до ожидаемого10:00UTC — проверять фактический UNLOCK в #12, не считать истёкшее ожидание освобождением.
- Наследованные moderation/QA/fix branches не объявлены свободными или законченными только из-за отсутствия нового сообщения. Уточнять владельцев через #12.

## Активные задачи

- #13: execution-time bootstrap expiry — реализация и тесты готовы, PR #16 ожидает независимого review/CI и безопасной интеграции.
- #14: captured lease для declare/peace/surrender/capture, фильтрация profile-pending/revoked участников и committed cache publication — IN_PROGRESS у второго агента.
- #15: реестр доказательств и полной релизной приёмки, открытые blockers.

## Занятые компоненты и файлы

- #13/agent-stability-20260906-122149: AuthRepository.java, AuthTest.java, AuthBootstrapExpiryTest.java, docs/AUTH_BOOTSTRAP_EXPIRY.md; первоначальное создание этого AGENT_STATUS согласовано в #12. Перед последующими правками читать свежую версию и сообщения.
- #14/agent-stability-20260906-122256: WarRepository.java, WarService.java, только WarService constructor wiring в CoreRuntime.java; WarLeaseTest.java, WarAuthorizationWiringTest.java, docs/WAR_SESSION_AUTHORIZATION.md.
- Не менять чужие компоненты без согласования. Общие файлы не редактировать параллельно.

## Открытые Issues

[#12 Coordination](https://github.com/ProkStudio/WarLand/issues/12), [#13 bootstrap expiry](https://github.com/ProkStudio/WarLand/issues/13), [#14 war authorization](https://github.com/ProkStudio/WarLand/issues/14), [#15 stable-release checklist](https://github.com/ProkStudio/WarLand/issues/15). Перед работой получить новый список: этот перечень — срез, не вечный реестр.

## Открытые Pull Requests

- [#16 bootstrap expiry](https://github.com/ProkStudio/WarLand/pull/16): узкий security fix, не release; verify для2419c8c прошёл в09:48:26UTC, независимый review выполняет второй агент. После нового документационного коммита проверить актуальный CI повторно.
- [#11 release continuation](https://github.com/ProkStudio/WarLand/pull/11): draft, накопленная интеграция.
- [#10 auth runtime](https://github.com/ProkStudio/WarLand/pull/10): draft, предыдущая ступень интеграции.

## Выполненные задачи

- Созданы Coordination #12, отдельные CLAIM/ветки #13/#14; установлена передача общего build-ресурса без вмешательства в чужие тесты.
- #13: воспроизведено принятие просроченных registration/provisioning на исходном коде; добавлены execution-time checks, финальный SQL expiry/hash CAS и 14 регрессий. Схема, runtime admission API и feature flags не менялись.
- Подтверждена ранее завершённая сборка base7acf914: Java370 detected/4skips/0failures, Python78. Это не доказательство новых изменений.

## Следующие приоритетные шаги

1. Завершить #14 у текущего владельца; не присваивать его файлы/ресурс. Передать независимый review PR #16 и будущего war PR через GitHub.
2. После CI/review согласовать интеграцию, собрать точный общий SHA и выполнить свежие isolated runtime matrices; старые JAR результаты не переносить на новый бинарник.
3. Проверить наследованный offline-owner moderation/queued actor callbacks. Брать отдельную задачу только после проверки CLAIM.
4. Закрывать полный checklist #15 доказательствами: реальные клиенты/Mojang/UI, inventory/crash/market, beta10/20/30, backup/restore, миграция UUID и rollback. До этого стабильного релиза нет.

## Известные ошибки

- Bootstrap expiry исправлена в PR #16, но ещё отсутствует в интеграции и live runtime.
- War queued authorization/capture participation — #14 в работе. Первый JUnit401 с3 failures вызван неверной owner-fixture: смена rank не отзывает права durable owner. Агент исправил fixture на делегата и добавил отзыв custom permissions, production policy не ослаблялась; итог повторной сборки ещё ожидается.
- Незавершённый optional MarketFeature/покупки/инвентарные границы; реальные Mojang/companion UI/load и ряд crash/TTL/respawn сценариев не приняты.
- Старые startup lag/disabled-module warnings требуют классификации на точном новом runtime; отсутствие свежего просмотра логов не означает отсутствие ошибок.

## Технический долг

Некоторые docs/HANDOFF описывают прошлые этапы и уже исправленные compileTestJava ошибки. Issues и точные коммиты имеют приоритет. Четыре существующих PlanSafetyTest с реальными registry/worldgen codec пропущены. Полное ТЗ, backup/restore для обновления рабочих данных и Paper→Fabric/UUID migration не закрываются unit-тестами.

## Состояние Minecraft-сервера

Последняя моя read-only проверка09:41:15UTC: `minecraft.service` (Paper) и исходный `warland-staging.service` active. Их миры, конфиги, JAR и процессы не менялись. Реальный owner bootstrap/OP не выдавался. Полный релизный backup в этой сессии не создавался: для недеплойных тестов использовались только новые временные БД; старые backups сохранены. Перед deployment нужен актуальный согласованный backup и проверка восстановления.

VPS4GiB: тяжёлые Gradle/JVM/runtime — по одному под `/opt/warland-build/build.lock` и GitHub LOCK. Активный чужой LOCK нельзя обходить. `enableWarCapture`, buyer/AH и незавершённые покупки не включать.

## Последние значимые коммиты и ветки

- `7acf914`: текущий integration base; исправление прежнего compileTestJava fixture, после `be37e553`, `bd0c1adf`, `aa9c6954` (starter/nation leases).
- `2419c8c`: #13, branch `agent/agent-stability-20260906-122149/bootstrap-expiry`, PR #16. Все165 tracked файлов remote сверены с протестированной копией.
- #14 branch `agent/agent-stability-20260906-122256/war-session-authorization`: на момент подтверждённого checkpoint опубликованный patch ещё не принят; читать фактический HEAD и #14.
- Старые `fix/treasury-auth-continuation`, `fix/starter-grant-continuation`, `fix/moderation-auth-continuation` не cherry-pick как готовые только по названию.

## Проведённые тесты

Для точного #13 source2419c8c:
- Red/base7acf914:2 теста действительно падают на принятии просроченных запросов; Java compilation успешна.
- Green: clean test build успешен; JUnit384 detected,380 passed,4 preexisting skips,0 failures/errors; все14 новых expiry/queue/KDF/rollback/restart tests прошли.
- Python78/78 с `PYTHONPATH=tools`. Первая ошибочная invocation без PYTHONPATH дала import errors; лог сохранён, затем повторена штатная CI-команда без изменения тестов.
- `git diff --check` пройден; SHA256 executable JAR `bb34df49bfd96c6bcae8f28103e4548c8daf4530c58d5bcd1e9d3be0b7b615b3`.
- Evidence manifest SHA256 `2c10a780352e1699c0e583f148f3f97ffe88561f8eaca189bddd94a699ee5ee6`; подробности — docs/AUTH_BOOTSTRAP_EXPIRY.md и #13.

Для #14 результаты принадлежат второму агенту: первый Java401/3failures/4skips, Python78/78; к09:48 исправлена owner-fixture и запущен полный повторный прогон, результат ещё не получен. Это не мои выполненные тесты.

## Инструкции по запуску и проверке

JDK21, Python3; отдельный checkout:

```bash
python3 tools/build.py clean test build
PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v
```

В проверенном cached окружении допустим `--offline`. VPS checkout #13: `/opt/warland-build/agent-stability-20260906-122149-bootstrap-expiry`; его HEAD может оставаться base7acf914 с точной проверенной рабочей копией — remote2419c8c отдельно fetched и все файлы сверены. Не смешивать checkout с чужими dirty trees. Evidence — prefix `agent-stability-20260906-122149-{red,green,python,evidence}` в `/opt/warland-build`.

Запуск runtime — только по docs/OPERATIONS.md, docs/PRIVATE_CONSOLE.md, fresh isolated path и предварительным LOCK; не устанавливать JAR в рабочий сервер автоматически.

## Инструкции по откату

#13 не развёрнут; сейчас откат — не устанавливать PR. Миграций схемы нет. Позднее согласовать backup/restore, runtime smoke и restart window; при возврате старого JAR сохранять новое состояние игроков/БД отдельно. Старый JAR возвращает expiry-дефект. Не удалять worlds, player data, accounts, audit, backups или чужие ветки. Не force-push.

## Инструкции для следующего агента

Сначала #12, последние комментарии всех активных задач, PR/checks/ветки/коммиты и свежий AGENT_STATUS. Придумать свой ID, зарегистрироваться, CLAIM отдельной задачи; нет DONE/RELEASE — компонент занят. Публиковать HEARTBEAT/REQUEST/RESPONSE; перед shared actions перечитать координацию. После действий снять свои LOCK, сохранить проверяемые результаты и ограничения. Не выдавать прошлый synthetic evidence за новый JAR или реальную приёмку. Этот файл обновлять целевым изменением, сохраняя чужие актуальные записи.

## Дата последнего обновления

2026-09-06T09:48:31Z (12:48:31 Europe/Moscow), `agent-stability-20260906-122149`. GitHub Issues/комментарии приоритетнее этого среза.
