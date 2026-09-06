# Срок одноразового bootstrap-кода владельца

Задача [#13](https://github.com/ProkStudio/WarLand/issues/13), координация [#12](https://github.com/ProkStudio/WarLand/issues/12). Исполнитель: `agent-stability-20260906-122149`. База: `feature/release-continuation` / `7acf914af7d6676121e09f11b2533d8200be6907`. Проверенный опубликованный код: `2419c8c34ea4c70a0819a0a0341e2d121f05f506`, [PR #16](https://github.com/ProkStudio/WarLand/pull/16); все 165 tracked файлов remote побайтно совпали с проверенной копией.

## Дефект и исправление

Ранее `AuthRepository` сравнивал срок challenge с переданным временем запроса, захваченным до KDF/очереди Store. Просроченное к моменту выполнения доказательство могло привязать владельца; задержанное provisioning могло установить уже просроченный challenge.

Оба production-конструктора теперь используют серверный `System.currentTimeMillis`. Время читается внутри SQL-транзакции; время запроса остаётся лишь нижней границей (`max(requestedAt, current)`). Package-private `LongSupplier` предназначен для детерминированных тестов, а не клиентского ввода. Неположительное время отклоняется.

- Provisioning не продлевает исходный срок и сохраняет предел 24 часа от запроса. Повторная проверка перед возвратом из транзакции откатывает замену challenge и audit при просрочке внутри SQL.
- Регистрация сначала проверяет актуальный срок и constant-time digest. Последняя SQL-запись перед commit — compare-and-set по свободной owner-записи, тому же digest и строгому `expires > executionTime`. Отказ откатывает аккаунт, привязку и успешный audit вместе.
- На точной границе `expires` код недействителен. Неистёкший proof по-прежнему погашается однократно. Схема БД, transport/session API, KDF, права, feature flags и приватный способ выдачи кода не менялись.
- Два исторических fixture-конструктора в `AuthTest` используют фиксированные часы. Существующие проверки не удалены и не ослаблены.

## Фактические проверки

Проверено 2026-09-06 в отдельном checkout, от непривилегированного пользователя, с одной сборкой под общей `build.lock`. Использованы только свежие временные тестовые БД и синтетические данные.

1. **Red на исходном production-коде 7acf914:** Java и тесты компилируются; два новых теста падают именно потому, что registration/provisioning не отклоняют просроченные запросы. Exit 1, JUnit 2 tests / 2 failures / 0 errors. Это подтверждение дефекта, не ошибка компиляции.
2. **Исправленные исходники:** `clean test build` — успешно; JUnit 384 обнаружены, 380 прошли, 4 существующих пропуска, 0 failures/errors. Все 14 новых bootstrap-тестов прошли: default clock, очередь БД/KDF, точная граница и последняя допустимая миллисекунда, финальный SQL rollback, one-time consume, restart, нижняя граница времени запроса, invalid clock и max TTL.
3. **Python:** 78/78 прошли с `PYTHONPATH=tools`. Первоначальная команда без этого объявленного в CI параметра дала import errors; её лог сохранён. Исправлялась только команда запуска, не код и не проверки.
4. `git diff --check` прошёл. Исходники на компьютере и VPS сверены SHA256.

Четыре старых пропуска — `PlanSafetyTest` с настоящими Minecraft registry/worldgen codec. Они не относятся к bootstrap и остаются открытой границей приёмки.

Проверенные SHA256:

- `AuthRepository.java`: `4523728ecc7eb82df6fcb8b2d2303c741c4ef2288ead6a0ded4737df0e785ae9`
- `AuthTest.java`: `6e8d12b6c00b274fe260135443f0e6578ca4367800f8a9d2d4c838da7503b155`
- `AuthBootstrapExpiryTest.java`: `0223d42ace1f232d0165d149abc844fc90a03f20f17ea377b491c8229933e4f7`
- Проверенный executable JAR: `bb34df49bfd96c6bcae8f28103e4548c8daf4530c58d5bcd1e9d3be0b7b615b3`

Локальные evidence на VPS: `/opt/warland-build/agent-stability-20260906-122149-red.*`, `...-green.*`, `...-python.*`, `...-evidence/`. Полные server logs/конфиги/БД/миры и реальные учётные данные в репозиторий не копируются.

## Как повторить

Нужны JDK 21 и Python 3. В отдельном checkout:

```bash
python3 tools/build.py clean test build
PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v
```

При подготовленном проверенном Gradle/dependency cache допустим `--offline`, как в этой VPS-проверке. Для одного нового набора: `python3 tools/build.py test --tests ru.warland.auth.AuthBootstrapExpiryTest`.

Общий VPS: сначала проверить #12 и занятые ресурсы, затем собственный `LOCK`, отдельный путь, непривилегированный пользователь, один worker и общая `build.lock`. После проверки — `UNLOCK`. Чужие worktrees и evidence не переиспользовать и не очищать.

## Ограничения и откат

Это unit/build-проверка, не новая dedicated Fabric/Mojang/GUI/load/production-приёмка. Рабочие Paper и исходный Fabric staging не перезапускались, реальные bootstrap/owner/OP не создавались, runtime JAR не заменялся. Готовность стабильного релиза не заявляется: общий checklist — [#15](https://github.com/ProkStudio/WarLand/issues/15).

Линеаризация срока — последняя защищённая SQL-запись/проверка перед commit, не физическое окончание fsync. Для сохраняемых абсолютных сроков требуется корректное системное время VPS; это не отдельная защита от произвольного отката wall clock между запросами или рестартами. Уже существующие owner-привязки не отзываются автоматически.

Миграция БД не требуется. Пока PR не развёрнут, откат — просто не устанавливать артефакт. Для активации принятого JAR позднее нужен согласованный restart с актуальным backup и проверкой восстановления. Возврат предыдущего JAR совместим со схемой, но возвращает дефект expiry; не изменять и не удалять аккаунты, audit или player data ради отката.
