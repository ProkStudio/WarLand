# WarLand — исправление ожидания при входе

2026-09-06, продолжение сообщения пользователя «висит бесконечное ожидание ответа от сервера». Этот файл дополняет RUN_STATE.md; исходные release tags/assets не перезаписаны. Stable #15 остаётся незавершённым.

## Проверенный кандидат
- Hotfix `0.1.0-alpha.3.1`; код `79580f8c1f97cc446d32e44031660e5710da6a7a`, ветка `agent/release-20260906-1633/auth-dialog-wait`.
- JAR SHA256 `65a9627f959e5767290eda576766d98a99807097f072eb5730f1e62402b85e77`.
- GUI больше не переводится в отдельный WAIT_FOR_RESPONSE; сохранена форма с NONE. Добавлены сообщения о длине пароля, несовпадении подтверждения, формате кода и двухминутном тайм-ауте. Timeout отправляет protocol disconnect. Nonce/schema/encryption/limits/owner proof/pre-PLAY barrier НЕ ослаблены.
- Exact source byte-match проверен; clean Java479 detected/475passed/4прежних registry skips/0failures, Python210 PASS. Первый запуск имел2ошибки новых UI-тестов из-за отсутствия Minecraft registry bootstrap в plain JUnit: сохранён как FAILED, не скрыт. Plain-JVM тесты проверяют input policy/source wiring; фактическое создание/кодирование диалогов проверено booted native-wire сценарием.
- Новый изолированный synthetic owner: login denial → short-password feedback → wrong proof denial → правильная registration → обязательный pack → PLAY/balance; штатный restart → wrong password denial → login. Compression256, каждый initial/denied dialog действительно кодирует after_action=none; одна synthetic стартовая выплата, identity/SQLite/FK сохранены, оба shutdown0, без ошибок/утечки canaries. Это не доказательство графической приёмки на компьютере пользователя.

## Развёртывание
Запущена проверяющая процедура; результат ещё нужно прочитать, НЕ считать этот checkpoint подтверждением успешного deployment.
- `/opt/warland-ops/auth-dialog-hotfix-20260906/{result.json,deploy.exit,deploy.log,deploy.pid,deploy.py}`.
- Только `warland-alpha.service`, zero-online preflight, private full runtime archive+tar compare, старый JAR сохранён отдельно. Проверка прежних аккаунтов/балансов/identity и post-start native NONE/cancel. При ошибке откат только кода: новые данные игроков НЕ восстанавливаются из старой БД.
- Публичный адрес `201.51.10.116:25565`, обычный Minecraft Java1.21.11. После успешного deployment пользователь должен полностью переподключиться: старый открытый диалог не заменяется автоматически.

## Продолжение
- Checkout `/opt/warland-build/auth-dialog-fix-20260906-1643`; QA `/opt/warland-build/vanilla-dialog-qa-owner-hotfix-v2/result.json`; exits `/opt/warland-build/auth-ui-fix-{java,python,runtime}-v2.exit` =0.
- Сохранённые QA helpers в checkout/tools: owner_retry_smoke.py, owner_retry_hotfix_smoke.py, compressed_wire.py; они пока untracked, не выдавать их за часть опубликованного коммита.
- Прочитать deployment result, проверить service и новую форму; после успеха обновить этот файл и снять собственные LOCK в #12.
- Прежний одноразовый owner code истёк во время диагностики. Если реальный owner ещё не bound, получить новый через PRIVATE_CONSOLE и передать владельцу приватно. Пароли/коды/identity.bin/БД/архивы/личные данные НЕ публиковать. Не регистрировать реального владельца синтетическим QA-паролем и не выдавать OP по нику.
- Ветка содержит код hotfix, но отдельного опубликованного release asset для3.1 пока нет. Старые alpha.3 assets описывают прежний JAR. Полная интеграция main/PR40 и stable-механики остаются следующими задачами; не объявлять stable готовым.
