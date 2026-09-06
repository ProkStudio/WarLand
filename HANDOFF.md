# WarLand — текущая работа и передача контекста

Обновлено 6 сентября 2026. Это точка продолжения, а не обещание фоновой работы после завершения диалога.

## Запрос

Довести проект до рабочего релиза, реализовать регистрацию/вход, затем защищённо привязать максимальную роль владельца к `egorkrid666`. Работать автономно и обновлять этот файл. Ник не доказывает личность; секреты не публиковать.

## Сейчас

**Код регистрации и игрового адаптера прошёл CI. Доступ к серверу восстановился; начата изолированная серверная проверка, релиз ещё не объявлен.**

- Интеграция: `development/initial-release`; `main` ещё не релиз.
- [PR9 — ядро](https://github.com/ProkStudio/WarLand/pull/9) объединён, merge `2faaff2070f648ef83f54292aeffd61fdd660e3d`. Кодовая ревизия `c4b547e8836468f147c0ddc16035f78edbb85ba6`: [PR verify](https://github.com/ProkStudio/WarLand/actions/runs/34017228254/job/101443035090), [branch verify](https://github.com/ProkStudio/WarLand/actions/runs/34017226357/job/101443029876) успешны.
- [PR10 — игровой допуск](https://github.com/ProkStudio/WarLand/pull/10), ветка `feature/auth-runtime`, **draft, не объединён/не развёрнут**. Ядро уже подтянуто merge-коммитом `1dfc5ad95978d01523541de3c2425dd3abe660c1`; не переносить старую Authentication.java поверх нового движка.
- Последний код PR10: `1c034feac191e328bc712e57af98ce8aed956b29`. Оба verify успешны: [PR job](https://github.com/ProkStudio/WarLand/actions/runs/34018268394/job/101445930803), [branch job](https://github.com/ProkStudio/WarLand/actions/runs/34018267001/job/101445926678). Полные численные итоги текущих XML ещё не извлечены.
- Подробный технический план: [runtime handoff](https://github.com/ProkStudio/WarLand/blob/feature/auth-runtime/docs/HANDOFF_AUTH_RUNTIME.md). Его первоначальный текст устарел относительно merge9 и последнего hardening; этот раздел имеет актуальные SHA/статусы.

### Немедленное продолжение

На VPS от `warland-build` запущена отдельная сборка точного коммита 1c034fe, PID при старте **33447**. Не запускать повторно вслепую:
- Скрипт `/opt/warland-build/auth-runtime-1c034fe-build.sh`.
- Лог, статус и PID: `/opt/warland-build/auth-runtime-1c034fe-build.{log,exit,pid}`.
- Новый изолированный checkout: `/opt/warland-build/auth-runtime-1c034fe` (detached SHA, чужие worktree не менялись).
- Итоги тестов по завершении: `/opt/warland-build/auth-runtime-1c034fe-tests.json`.
- Используются общий `build.lock`, Gradle9.2.1, Java21, один worker, heap640M, timeout900s. Проверить итог, ошибки и JAR hash.

Следом подготовить/запустить отдельную копию **синтетического** `/opt/warland-build/release-qa/final-runtime` для boot/Mixin/negative-packet/restart проверки нового JAR. Порт127.0.0.1:25569 был свободен при восстановлении доступа; перепроверить перед запуском. Не использовать и не заменять production/staging runtime. Положительный encrypted/online client handshake и реальная привязка владельца требуют отдельной приёмки; offline rejection не считать успешным игровым входом.

## Что реализовано

- PBKDF2-SHA256/600000, соль128бит, пароли12–128 символов; хеш/аудит/UUID+имя в SQLite, одноразовая приватная привязка владельца.
- Вся auth-цепочка ограничена9 операциями ещё до Store. Cancel/close очищают ожидающие секреты; работающий KDF очищает их при выходе. Permit удерживается до завершения внутренней операции, поэтому reconnect churn не раздувает очередь БД. Есть тесты отмены, заблокированной БД, shutdown и nonce.
- В PR10: обязательный CONFIGURATION task до появления игрока в мире; отдельный маскированный экран клиента; шифрование **и online-mode**; нет парольных команд. Профиль/стартовые деньги отложены до auth, gameplay — до profileReady.
- Добавлены packet/TTL/nonce/simulation/pickup/damage/death/async guards, отдельная проверка обоих переходов `onReady` и `endConfiguration`. Проверены имена/поля в Yarn1.21.11 и ранняя task-очередь Fabric API0.141.6+1.21.11; это не замена живому Mixin-weaving.
- Консольный источник требует реального server output, отсутствия entity, несокрытого контекста и OWNERS permission. Автоматические/ограниченные function contexts не должны становиться владельцем.
- Смена/сброс пароля **не экспонированы**: сначала согласовать CAS и отзыв всех pending/active сессий с login publication.

## Владелец

В development до merge PR10 ещё прежний runtime. В PR10 максимальные WarLand staff permissions требуют сохранённого owner UUID и текущей авторизованной/profileReady сессии; vanilla OP не выдаётся и не служит owner fallback. Приватная console-команда после приёмки создаёт15-минутный bootstrap-файл0600 в каталоге0700. Проверить реальный операторский канал, replay/expiry, источники и POSIX failure handling. **Производственный bootstrap не создан; egorkrid666 не привязан; OP/роль не выданы.**

## Проверенное ранее

[PR8 — рынок](https://github.com/ProkStudio/WarLand/pull/8) объединён, `af2eafc1e89fb9f8bf549bba8289fb26f6149dac`. Escrow/почта, intent/recovery, атомарные сделки/комиссия/отмена/истечение. [MARKET_ESCROW](docs/MARKET_ESCROW.md).

Серверные тесты того среза:296 Java обнаружены,292 успешны,4 старых пропуска;30 Python успешны; все39 новых тестов рынка успешны. Числа относятся к рынку, не к текущему auth-коду.

После восстановления доступа прочитана дополнительная старая проверка: `/opt/warland-build/market-escrow-smoke.exit` =0; `market-escrow-smoke/market-smoke-result.json` — два цикла, оба exit0/ready=true/errors=[], одна БД проверена. Artifact SHA256 `35db40a10f00ae0cbf8a1f4b18ffdb10f171eae2980de6e6cd27bec7f88a4395`. Это loopback boot/console/restart, **не live inventory acceptance**. Финансовые флаги не включены.

## Инфраструктура и осторожность

HTTP429 временно блокировал Minecraft/root shell; затем чтение восстановилось. `minecraft.service` (Paper) и `warland-staging.service` (Fabric) подтверждены active. Они не перезапускались и не заменялись. Не трогать чужие dirty worktrees, не делать reset/clean в старых каталогах; market-escrow содержит проверенные, возможно untracked файлы.

В sandbox нет команды javac, но работает `java -m jdk.compiler/com.sun.tools.javac.Main --release 21`: PasswordProbe прошёл; RuntimePolicy скомпилирован. Это не полная Fabric-проверка. Записи файлов на computer выполнять последовательно и сверять hashes: ранее параллельные локальные записи потеряли boundary-патч, хотя GitHub сохранил его. У дочернего автора был отдельный sandbox; его исходники опубликованы в PR10.

## Остальные релизные ворота

1. Live server/client auth, все packet/TTL/crash/reconnect/respawn/legacy-async эффекты, безопасная реальная привязка владельца.
2. ItemStack/NBT-кодек, durable player-data read-back, полный InventoryGuard и crash-матрица; GUI рынка/скупщик/почта/покупки.
3. Города/содержание/улучшения, дипломатия и военные регрессии.
4. Клиентский модпак/ресурспак, техника/управление/HUD, ремонт/авиация/зачарования.
5. События/квестовые цепочки/рейтинги/сезонная косметика.
6. Модерация/апелляции/античит/rollback и восстановление данных.
7. Настоящая бета10/20/30, профиль/баланс и безопасный согласованный Paper→Fabric переход с учётом UUID/auth.

Общий статус: интеграционная alpha, не рабочий production-релиз. [RELEASE_STATUS](docs/RELEASE_STATUS.md).
