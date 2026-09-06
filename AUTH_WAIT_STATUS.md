# WarLand — исправление ожидания при входе

**Обновлено 2026-09-06, 20:05 МСК: hotfix 0.1.0-alpha.3.1 установлен, сервер готов.** Этот файл дополняет более ранний RUN_STATE.md и является актуальной точкой по проблеме входа. Полный stable #15 по-прежнему НЕ готов.

## Игроку
- Сервер `201.51.10.116:25565`, обычный Minecraft Java1.21.11; никаких клиентских модов. Ресурс-пак скачивается после штатного согласия.
- Полностью отключиться и подключиться заново: старое открытое окно не заменяется автоматически.
- Первый вход: свой отдельный пароль12–128символов, точный повтор, «Регистрация». Зарезервированному владельцу нужен приватный одноразовый код; другим поле кода оставить пустым. Поле пароля не маскируется.
- Старый15-минутный код истёк во время диагностики. Передавать новый только приватно и только пока реальный owner не bound. Никогда не публиковать код/пароль и не регистрировать реального владельца синтетическим QA-паролем. Реальный вход пользователя после исправления ещё требует подтверждения.

## Что изменено
Форма больше не переводится на отдельный WAIT_FOR_RESPONSE: actual dialog остаётся на экране с NONE. Добавлены подсказки о длине пароля, несовпадении подтверждения и формате кода. Двухминутный timeout отправляет protocol disconnect с понятным текстом. Nonce/schema/encryption/session/rate limits/owner proof/pre-PLAY barrier НЕ ослаблены. Нет OP по одному нику, миграции БД или включения незавершённых функций.

## Проверенный код и acceptance
- Exact source `79580f8c1f97cc446d32e44031660e5710da6a7a`, ветка `agent/release-20260906-1633/auth-dialog-wait`; файлы байт-в-байт сопоставлены с GitHub.
- Deployed JAR SHA256 `65a9627f959e5767290eda576766d98a99807097f072eb5730f1e62402b85e77`.
- Clean Java479 detected/475passed/4прежних registry skips/0failures; Python210 PASS. Первые2ошибки новых GUI-конструкторных тестов были вызваны отсутствием Minecraft registry bootstrap в plain JUnit; FAILED evidence сохранено. Plain-JVM проверки честно разделены на input policy/source wiring, реальное создание и кодирование диалогов проверено на booted Fabric.
- Новый isolated synthetic owner: login denial → short-password feedback → wrong proof denial → registration → обязательный pack → PLAY/balance; graceful restart → wrong password denial → login. Compression256, каждый initial/denied dialog действительно кодирует after_action=none. Одна synthetic выплата1500, stable identity, SQLite/FK clean, два shutdown0, нет ошибок/утечки canaries. Это НЕ графическая приёмка на устройстве пользователя.

## Фактическое развёртывание
- `/opt/warland-ops/auth-dialog-hotfix-20260906/result.json`: success=true, phase=complete, rollback_used=false. deploy.exit=0.
- `warland-alpha.service` active/running, запуск17:04:06UTC, WarLand ready17:04:30UTC, version0.1.0-alpha.3.1.
- Приватный полный runtime backup `/var/backups/warland-auth-dialog-20260906-170355`, tar read-back comparison выполнен; SHA256 `2b8c930c04c767c2bd54c8a1921c5ddf551e56e14950056dc354f3b3cc7125e7`. Это проверенный архив текущего hotfix baseline; новый booted restore именно этого архива не заявляется. Более ранний alpha3 booted restore записан в RUN_STATE.
- Private identity и все baseline rows профилей/аккаунтов/балансов/ledger/owner сохранены. Startup errors=[]; production native probe увидел NONE-форму и корректную отмену без создания аккаунта.
- Только code swap. Старый JAR сохранён в runtime/retired-auth-dialog-20260906-170355. Для rollback остановить только этот сервис и вернуть старый JAR, сохранив всю актуальную БД/миры. Никогда не восстанавливать старую БД поверх новых действий игроков.

## Продолжение разработки
- Checkout `/opt/warland-build/auth-dialog-fix-20260906-1643`; full build/runtime exits `/opt/warland-build/auth-ui-fix-{java,python,runtime}-v2.exit`=0; QA `/opt/warland-build/vanilla-dialog-qa-owner-hotfix-v2/result.json`.
- Приватная deployment-процедура и evidence: `/opt/warland-ops/auth-dialog-hotfix-20260906/{deploy.py,result.json,deploy.log,deploy.exit}`.
- QA helpers в checkout/tools: owner_retry_smoke.py, owner_retry_hotfix_smoke.py, compressed_wire.py пока untracked. Сохранить и оформить отдельно; не выдавать за часть source79580.
- Отдельного опубликованного release asset3.1 пока нет; immutable alpha.3 tag/assets НЕ перезаписывались и содержат прежний JAR. Main пока docs-first, PR40 требует отдельного разрешения конфликтов. Полная интеграция/публикация согласованного нового release и stable-механики — следующий этап.
- Рынок/скупщик/AH, покупки/захват, полноценные crash/recovery, авиация/final HUD и beta10/20/30 не завершены; flags не включать ради релиза. Историю, чужие worktrees, Paper/staging, worlds/backups/keys сохранять.
- После завершения диалога фоновой разработки нет. Координация и освобождение собственных ресурсов — #12; актуальность чужих CLAIM проверять перед новой работой.
