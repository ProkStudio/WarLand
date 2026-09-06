# Авторизация отложенных военных операций

Задача: #14. Агент: `agent-stability-20260906-122256`. База: `feature/release-continuation`, `7acf914af7d6676121e09f11b2533d8200be6907`.

## Исправляемая граница

`WarRepository.declare`, `peace`, `surrender` и `capture` больше не являются безусловными system-транзакциями. На входе захватывается существующая `AuthRuntime.lease`: конкретная connection/session/nonce, PLAY attachment и загруженный профиль. На последовательном DB-worker `Store.tx(lease, work)` проверяет именно этот захваченный допуск **до** начала транзакции. Новая сессия с тем же UUID не авторизует старый запрос.

Factory вызывается на вызывающем серверном потоке. На DB-worker не выполняется новый поиск игрока по UUID и не читается Minecraft world/entity. Исключение factory или отсутствующая lease означают отказ, а не system fallback. Права государства/членство, конфиг, окно, столица, пакт, связность и квота по-прежнему проверяются из SQLite внутри транзакции.

Допуск линеаризуется на DB-worker. Отзыв, произошедший после принятия уже выполняющейся операции, не отменяет committed данные. Их публикация в кэше не должна зависеть от выхода игрока или отмены наблюдающего future: `WarService.update` возвращает отдельную копию completion-stage. Отмена observer не является обещанием отмены экономической операции.

## Участники захвата

Перед чтением membership и добавлением игрока в snapshots `players`, `online`, `occupants`, `WarService.tick` проверяет `CoreRuntime.online`. Она включает readiness, авторизацию/profile-ready и идентичность текущего игрока в PlayerManager. Pending/revoked/disconnected участники не ускоряют/замедляют захват, не создают contest и не считаются защитниками online. Существующие проверки survival/alive сохраняются.

При постановке итогового capture в очередь дополнительно захватывается lease его участника. Исчезнувшие eligible цели удаляются через существующий `progress.retain(active)`; worker-отказ очищает pending capture через обычный callback. Это не меняет квоты, длительность мобилизации или отключённые feature flags.

## System-операции и совместимость

Истечение войн и initialize/settlement после restart не требуют online actor. Они остаются явными system-транзакциями, чтобы выход всех участников не замораживал завершение войны.

Public compatibility-конструктор `WarService` без явной live policy **запрещает** пользовательские действия. Production получает factory/predicate явно из CoreRuntime. Трёхаргументный package-private `WarRepository` сохранён только как явный trusted test/system fixture; live adapter его не использует.

## Проверки

- Детерминированная очередь над отдельной temporary SQLite WAL: revocation, same-UUID reconnect, pending→authenticated для всех четырёх операций.
- Полный снимок затрагиваемых таблиц до/после отказа; current durable rights, корректная новая сессия, factory failure/null lease.
- System settlement/initialize без авторизованных участников.
- Отмена observer и post-admission revocation не скрывают committed snapshot.
- Source-wiring guards для CoreRuntime/AuthRuntime, ранней фильтрации online/occupants и protected worker boundary.

Команда целевых тестов: `python3 tools/build.py test --tests 'ru.warland.war.*'`.
Полная проверка: `python3 tools/build.py clean test build`, затем `PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v`.

### Фактический результат, 2026-09-06

В собственной изолированной VPS-копии выполнено `gradle --no-daemon --max-workers=1 clean test build`: **BUILD SUCCESSFUL, 404 detected / 400 passed / 4 preexisting skipped / 0 failures / 0 errors**. Из них `WarLeaseTest` — 29, `WarAuthorizationWiringTest` — 5, все успешны. Python suite с `PYTHONPATH=tools`: **78/78 passed**. Четыре старых skip находятся в `PlanSafetyTest` и относятся к реальным Minecraft registry/worldgen codecs; они не считаются пройденными.

Первый запуск имел три отказа новой fixture: владельца государства нельзя лишить полномочий простой сменой rank, поскольку durable owner имеет самостоятельные права. Исправлена именно fixture на не-владельца с явным GENERAL[war]; добавлены три проверки отзыва custom war permission. Production policy и исходные assertions не ослаблялись. First-run XML/исходник и оба набора логов сохранены отдельно.

Executable JAR SHA256: `3bee78f226f73edfcbe5f78946ffe2e7f34824a98c289dc5df50315776f3787b`.

Проверенный набор исходников — база `7acf914af7d6676121e09f11b2533d8200be6907` плюс следующие SHA256 (сама база не выдаётся за commit изменённого JAR):

- `WarRepository.java`: `5aeb361bfc675afb69eb41022c4792167f497fc60382191224b1b620a0b2627a`.
- `WarService.java`: `514076f1df6d76a9286728f2fceddb64c6077e78e924d9910d49da71405f3ec3`.
- `CoreRuntime.java`: `cb630f8250296fe22f1c31535e30e8ddc3680282ac506f8077d8a592e72f97b6`.
- `WarLeaseTest.java`: `328eb65d51fe85d833a4e51e27abe58c306837bb15a69b622920726e5208ff24`.
- `WarAuthorizationWiringTest.java`: `a4ed79ccc7377a5245e57f0874546cdccdc4c37617dc3a553b0633109c60a3f4`.

Побайтная сверка опубликованного commit и актуальный CI фиксируются отдельно в #14/PR. Bootstrap-изменение #13 не входило в этот запуск; общий integrated SHA требует своей проверки. Предупреждения deprecated Java/Gradle API остаются техническим долгом.

## Эксплуатация, ограничения и откат

Ни схема, ни существующие данные, ни конфиг не меняются. `enableWarCapture=false` сохраняется. Тесты используют новые синтетические SQLite-файлы; production/Paper/Fabric staging не затрагиваются.

Это не реальная многоклиентская battle/GUI/load acceptance и не полный релиз. Остаются реальные auth/TTL/disconnect/contest проверки с клиентами и ограничения общего release checklist. Включать войны ради этой проверки на рабочем сервере нельзя.

Откат до развёртывания — не устанавливать этот JAR. Если изменение позднее включено в кандидат: остановка только согласованного isolated runtime, сохранение его нового состояния, возврат предыдущего проверенного JAR без удаления данных; повторная проверка запуска. Перед production нужен проверенный backup/restore и общий release-deployment LOCK. Возврат уязвимого поведения не является безопасным production решением; feature flag должен оставаться выключенным.
