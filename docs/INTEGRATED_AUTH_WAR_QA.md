# Проверка общей auth/war-интеграции

Агент: `agent-stability-20260906-122149`. Задача [#17](https://github.com/ProkStudio/WarLand/issues/17), координация [#12](https://github.com/ProkStudio/WarLand/issues/12), полный release checklist [#15](https://github.com/ProkStudio/WarLand/issues/15).

**Промежуточная alpha: общий build и private-console PASS; encrypted/post-check BLOCKED. Стабильный релиз НЕ готов, deployment не выполнялся.** Проверка не покрывает будущие изменения после указанного source SHA.

## Происхождение артефакта

- Точный общий source: `e162219e4d137e2828fd04731427164fc33019fe` в `feature/release-continuation`.
- Родители merge проверены: `a1a2e039f9379c2eb8d3537a32ed58552722a2d8` (bootstrap expiry, PR #16) и `6258833a0d8e4a26266a1c516ec8a8a20364ac01` (war authorization, PR #18).
- Оба PR прошли независимый agent-ID COMMENT-review и актуальный GitHub verify до последовательного merge. Агенты работают через один GitHub account: это не формальный APPROVE и не обход branch protection.
- Для проверки создан отдельный checkout и ветка `agent/agent-stability-20260906-122149/integrated-auth-war-qa` от этого общего SHA. Все170 tracked файлов побайтно сопоставлены с Git-объектами после сборки; production/test исходники не менялись.
- Executable JAR SHA256: `ac64b404468afee949cf267744ea67c7ed4da978e0b83670f297ca4acb8d84e3`.
- Sources JAR SHA256: `b0f482b2abfc4a4888dc9e834b5d877b0299350d7f2a37be970b159efa295609`.

## Build и unit tests — PASS

2026-09-06, 10:18–10:20UTC: JDK21, Gradle9.2.1, отдельный непривилегированный checkout, nice/один worker и общая `build.lock` под GitHub LOCK. Pinned Gradle ZIP SHA256 и содержимое установленной distribution побайтно проверены до исполнения.

```bash
python3 tools/build.py --offline clean test build
PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v
```

Обе команды завершились exit0. GitHub verify для integrated e162219 также SUCCESS: runs34026569755 и34026567502 (10:10:48/10:10:36UTC). **JUnit418 обнаружены /414 прошли /4 существующих skips /0 failures /0 errors**,40 XML reports. Присутствуют и проходят все14 AuthBootstrapExpiryTest,29 WarLeaseTest и5 WarAuthorizationWiringTest. **Python78/78 passed.** `git diff --check` пройден.

Старые пропуски PlanSafetyTest остаются непроверенными в unit-контексте: `capitalDimensionDecodesWithActual12111WorldgenCodec`, `everyBlueprintStateResolvesAgainstActual12111Registry`, `onlyNaturalTransientValuesAreIgnoredByIntegrityCheck`, `airFluidAndUnknownBlocksFailClosed`. Boot нескольких fixtures не подменяет полную проверку всех registry/worldgen/blueprint границ.

Это новый общий418-run, а не сложение прежних384/404 результатов. В build log присутствуют предупреждения deprecated Java/Gradle API и Loom remapping; успешная сборка не означает, что технический долг устранён.

## Private console / bootstrap / restart — PASS

На свежей копии empty synthetic Fabric1.21.11 fixture выполнен штатный `tools/private_console_smoke.py`, loopback25576, только указанный JAR. В10:23UTC оба boot/stop cycles завершились exit0; runner exit0.

- Реальные console status и bootstrap команды дошли до Fabric; socket0600 и родительская директория0700.
- Одноразовый synthetic proof создан приватно; повторная команда не перезаписала существующий proof; canary не оказался в логах.
- Удалялся только plaintext canary, созданный в собственной одноразовой копии. Рабочие auth data/операторы не менялись.
- После каждого цикла:0 profiles/auth_accounts/accounts/ledger,0 bound owner/vanilla OP, SQLite quick_check=ok и foreign-key errors0; control socket после stop отсутствует.
- Оба startup logs содержат WarLand ready и штатный Done; ERROR/exception/injection error не обнаружены.

## Encrypted auth / restart

**BLOCKED / НЕ ПОДТВЕРЖДЕНО.** Запрос запуска через подключение VPS получил HTTP429; повторные read-only проверки также не удались. Факт старта и результат не установлены. Перед повтором обязательно проверить реальные PID/exit/output paths; вторую копию вслепую не запускать. Наличие build/private-console PASS не заменяет этот результат. В #12 запрошена независимая лёгкая сверка состояния у второго агента; тяжёлые действия не делегировались.

## Warnings и ограничения

1. На обоих private-console стартах отсутствует optional `ru.warland.economy.MarketFeature`; enableBuyer/enableAuction=false. Это известный функциональный blocker полного ТЗ, не работающий магазин и не основание включать покупки.
2. Startup lag в этих двух циклах:6360ms/127ticks и6101ms/122ticks. Наблюдение относится к low-resource QA с одной активной CPU и heap640MiB; нормальная работа под нагрузкой не доказана. Предупреждения не скрыты и не объявлены исправленными.
3. Нет real Mojang/companion GUI, многоклиентской войны, полной TTL/respawn/disconnect/crash matrix, изменённого startingBalance, inventory/escrow recovery или beta10/20/30 acceptance. Синтетический IDP, если использован, не заменяет настоящую проверку учётной записи Mojang.
4. `enableWarCapture=false` сохранён. Успешные lease unit tests не разрешают включать войны на рабочем сервере.
5. Production backup/restore, Paper→Fabric/UUID/account migration и performance gates остаются отдельными задачами. Рабочие данные не использовались как fixture.

## Изоляция и evidence

Read-only preflight подтвердил source fixture без profiles/auth_accounts/accounts/ledger/owner/OP, private-auth, symlinks и world playerdata/stats/advancements; quick_check=ok. Для будущего сравнения зафиксированы182 постоянных файла. Исключены только SQLite SHM и отсутствующий/пустой WAL; непустой WAL запрещён.

**Финальная постпроверка заблокирована доступом VPS.** Побайтное сравнение182 постоянных файлов после всех runtime-операций, актуальное состояние encrypted runner и освобождение ресурса пока не подтверждены. #17 остаётся открытой. GitHub LOCK vps-build не снят в отсутствие безопасной сверки; ожидаемое время не является автоматическим UNLOCK.

Последняя подтверждённая проверка исходных служб10:21:37UTC: Paper и исходный Fabric staging active, их PID не изменились. Их текущий health после HTTP429 не перепроверен. Private-console2 cycles и runner завершены exit0 — это отдельный подтверждённый факт, не гарантия состояния попытки encrypted запуска.

Evidence остаётся в собственных путях на VPS:
- `/opt/warland-build/agent-stability-20260906-122149-integrated-evidence/`: build-result,40 XML,170-file source manifest, fixture baseline и runner logs/exits/PIDs.
- `/opt/warland-build/private-console-qa-agent-stability-20260906-122149-e162219/`: два startup logs и private-console-result.
- `/opt/warland-build/auth-encrypted-qa-agent-stability-20260906-122149-e162219/`: только если этот отдельный запуск фактически выполнен.

Полные рабочие configs/БД/worlds/logs/секреты и player identifiers в Git не копируются. В опубликованном отчёте — только проверенные результаты, безопасные hashes и ограничения. Наши команды не удаляли и не перезаписывали исходную fixture, старые JAR/evidence/dirty trees; финальная побайтная постпроверка всё же остаётся незавершённой.

## Повторение и откат

Использовать JDK21, отдельный checkout **точного SHA**, новый GitHub LOCK и общую build.lock, нового непривилегированного владельца задачи и ранее не существовавшие QA-пути. Сначала build/Python и проверка JAR SHA256. Затем по одному запускать штатные tools/private_console_smoke.py и tools/auth_encrypted_smoke.py с прочитанными guardrails, параметрами `--source`, `--jar`, `--sha256`, `--out`, свободным loopback `--port` и явным `--synthetic-fixture`; private-console также требует `--source-sha`.

Единственный допустимый source этих harness — `/opt/warland-build/release-qa/final-runtime`; скрипты отказываются от непустой/неожиданной fixture и существующего output. У synthetic encrypted-проверки IDP overrides ограничены её собственной дочерней JVM. Никогда не переносить их в рабочий сервер.

JAR на рабочие Paper/Fabric staging не устанавливался; их restart и реальный owner/OP не выполнялись. Откат этой QA-сессии — штатно завершить только собственные test processes, сохранить evidence и освободить LOCK, не заменяя рабочий JAR и не удаляя пользовательские данные. Для будущего deployment требуются актуальный проверенный backup/restore, согласованное окно, отдельный release-deployment LOCK и закрытые реальные gates #15.

Срез отчёта: `2026-09-06T10:40:56Z`. #17 остаётся открытой; это частичный checkpoint.
