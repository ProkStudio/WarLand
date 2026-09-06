# WarLand — текущая работа и передача контекста

Обновлено 6 сентября 2026 после серверной проверки 07:28 UTC / 10:28 МСК. Это точка продолжения, не обещание фоновой работы после завершения диалога.

## Запрос и статус

Довести проект до рабочего релиза; сделать регистрацию/вход; затем защищённо выдать максимальную роль владельца `egorkrid666`. Работать автономно, сохранять продолжение здесь. Ник сам по себе не доказывает личность. Секреты не публиковать.

**Интеграционная alpha, ещё не production-релиз.** Ветка интеграции `development/initial-release`; `main` остаётся исходной спецификацией. [PR9](https://github.com/ProkStudio/WarLand/pull/9) объединён: `2faaff2070f648ef83f54292aeffd61fdd660e3d`. [PR10](https://github.com/ProkStudio/WarLand/pull/10), `feature/auth-runtime`, пока draft, не объединён и не развёрнут. Новый код не установлен в рабочие сервисы.

## Последний проверенный срез

Код PR10: **`1c034feac191e328bc712e57af98ce8aed956b29`**. Финальный engine PR9 включён merge-коммитом `1dfc5ad95978d01523541de3c2425dd3abe660c1`; не затирать Authentication.java старым вариантом.

- [PR CI](https://github.com/ProkStudio/WarLand/actions/runs/34018268394/job/101445930803) и [branch CI](https://github.com/ProkStudio/WarLand/actions/runs/34018267001/job/101445926678) успешны для этого кода.
- Свежая полная сборка VPS завершена, не работает в фоне: **337 Java обнаружены, 333 прошли, 4 старых пропуска, 0 failures/errors; 34 Python прошли**. Все41 auth-теста прошли без пропусков.
- Checkout: `/opt/warland-build/auth-runtime-1c034fe`; build driver и `{log,exit,pid}`: `/opt/warland-build/auth-runtime-1c034fe-build.*`; численные итоги: `/opt/warland-build/auth-runtime-1c034fe-tests.json`.
- Исполняемый JAR: `auth-runtime-1c034fe/build/libs/warland-0.1.0-alpha.1.jar` относительно `/opt/warland-build`. SHA256 **`d98d18790c4804c9c04b91d656e356e44db7f7c3c99b8742079d069a0ea32bbe`**. Не устанавливать sources JAR. Без изменения кода повторная сборка сейчас не нужна.

## Завершённая живая проверка

Отдельная синтетическая копия Fabric, только `127.0.0.1:25569`, от `warland-build`. Источник копирования: `/opt/warland-build/release-qa/final-runtime`, НЕ staging/production. Протокол774 извлечён из version.json Minecraft1.21.11.

**V2: два цикла boot/console/negative-packets/private-bootstrap/stop, включая перезапуск; оба exit0.** Оба клиента достигли CONFIGURATION и получили именно отказ WarLand; PLAY/Finish до auth не наблюдался. `ProbeInsecure` объявляет каналы WarLand и `fabric:registry/sync`, но offline/unencrypted transport отклонён. `ProbeEarly` отправляет преждевременный READY и получает отказ завершить защищённую авторизацию. Это отрицательные тесты, НЕ успешный парольный вход.

В каждом цикле консольная команда создала только синтетический43-байтный proof: файл0600, каталог0700; повторная команда не перезаписала файл; plaintext удалён. Profiles/auth_accounts остались0; OP пуст; SQLite quick_check=ok, FK errors0. ERROR/Exception не обнаружены. **WARN остались**: отсутствующий отключённый MarketFeature, намеренный offline mode, cold-start задержка примерно7сек и повторный handleDisconnection. Нагрузочная/клиентская приёмка не проведена.

Доказательства на VPS, НЕ перезаписывать:
- `/opt/warland-build/auth-runtime-smoke-1c034fe-v2.py`; SHA256 `771280c74caa434fa986608c05e5de365bb48ed2d83a7943df9c5190d0968aa3`.
- `/opt/warland-build/auth-runtime-smoke-1c034fe-v2.{log,pid,exit}`; exit=0, процесс завершён.
- `/opt/warland-build/auth-runtime-smoke-1c034fe-v2/auth-boot-{1,2}.log`.
- `/opt/warland-build/auth-runtime-1c034fe-smoke-v2-result.json`; SHA256 `9e4c4a5df832371948e3ec236c29b0d0ec3661b39a303516e7772ae853d845e9`.

V1 тоже завершён, но его ProbeNoMod отклонён проверкой зависимостей Fabric, не доказанной transport-проверкой WarLand. Не преувеличивать поле success в старом отчёте. V2 исправляет критерий отказа, partial-frame buffering, абсолютный timeout и защиту предыдущих отчётов при повторном запуске. V1 нельзя слепо перезапускать: его finally может переписать старый отчёт. Все старые файлы сохранены.

## Немедленное продолжение

1. Положительный **online-mode + encrypted** dedicated-client проход: регистрация, отказ неверному паролю, вход после перезапуска, ровно один профиль/стартовый грант, переход CONFIGURATION→PLAY и профиль-ready control packets. Не отключать защиту ради зелёного теста. Для автоматизации можно исследовать строго изолированный тестовый identity-provider; такой тест будет synthetic, не реальной проверкой Mojang/пользователя.
2. Настоящий companion-client/UI, packet/TTL/reconnect/respawn/crash и отложенные legacy-эффекты. Приватная процедура оператора и реальные источники console/function/RCON/command-block. [Подробное продолжение](https://github.com/ProkStudio/WarLand/blob/feature/auth-runtime/docs/HANDOFF_AUTH_RUNTIME.md).
3. После приёмки — безопасный выпуск/план миграции и реальная привязка владельца. **Production bootstrap не создан; egorkrid666 не привязан; роль/OP не выданы.** Тестовые proof к этому отношения не имеют.

## Инфраструктура и ограничения

`minecraft.service` (Paper) и `warland-staging.service` (Fabric) active; не перезапускались/не заменялись. После V2 порт25569 свободен, тестовые процессы завершены. VPS около4GiB RAM/2GiBswap; Java21/Gradle9.2.1, один build worker/heap640M. Не трогать dirty worktrees `core`, `release-integration`, `market-escrow`, реальные миры и конфиги. HTTP429 ранее был, сейчас соединение работает; не считать старую ошибку текущим статусом.

Computer иногда терял локальный файл между вызовами. Рабочий обход: создание+AST-проверка+упаковка в одном последовательном terminal-вызове, без параллельных computer операций. GitHub и VPS — долговременные источники. Сверять hashes при переносе; ошибка base64 в первой передаче V2 произошла до запуска, затем исправлена и проверена SHA256.

## Остальной объём релиза

Рыночный engine [PR8](https://github.com/ProkStudio/WarLand/pull/8) объединён и проверен; реальные inventory-финансы выключены. Остаются ItemStack/NBT/read-back/InventoryGuard/crash-матрица и рынок/скупщик/почта; города/дипломатия/война; клиентский модпак/ресурсы/техника/HUD/ремонт/авиация; события/квесты/рейтинги; модерация/rollback/backup recovery; настоящая бета10/20/30 и безопасная Paper→Fabric/UUID миграция.

[Предыдущий полный снимок истории и ограничений](https://github.com/ProkStudio/WarLand/blob/1df67e8356dd025bccf9f76cf89bfd19f729c62a/HANDOFF.md). [RELEASE_STATUS](docs/RELEASE_STATUS.md). Смена/сброс пароля не экспонированы до сериализации CAS/session-revocation/login publication. Никакой full-release/owner-grant приёмки пока не заявлять.
