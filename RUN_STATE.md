# WarLand — текущая точка продолжения

Обновлено 2026-09-06, после 17:43 МСК. Работа по #39. Полный stable по ТЗ ещё НЕ готов; текущая цель — проверенная vanilla alpha.3 с автоматическими загрузками.

## Где код
Ветка `agent/release-20260906-1403/vanilla-autodownload`, checkpoint `a2e725e8cd3713089233e917a11d2f93f0d8f5da`.
- NativeAuthDialog и guarded AuthRuntime/packet mixins; 22 новых Java-регрессии схемы, nonce, ограничений, отмены и обращения с секретами.
- Polymer 0.15.2+1.21.11: серверные оружие/магазины совместимы с vanilla-клиентом. Серверная отдельная LGPL-3.0-only зависимость; атрибуция/ограничения в docs/VANILLA_ALPHA.md.
- Воспроизводимый `tools/package_resources.py`: Minecraft 1.21.11 format75.0; 16 тестов хешей/повторяемости/путей/отказа от чужих файлов. Локально 16/16 прошли.
- `tools/vanilla_dialog_smoke.py`: изолированная native wire/pack-download/restart матрица. Это НЕ графический клиент; результат нужно читать, а не предполагать.

## Подтверждено
- MCP после новой ссылки снова отвечает. Рабочая `warland-alpha.service` остаётся active, это alpha.2; production этой сессией ещё НЕ обновлялся.
- Первый составной candidate: Java464 detected /460passed /4pre-existing skips /0failures/errors, Python156passed; BUILD SUCCESSFUL. Последующая точная published source имеет отдельный прогон.
- Исходная незавершённая копия `/opt/warland-build/vanilla-alpha-20260906` сохранена.
- НОВАЯ копия `/opt/warland-build/vanilla-release-20260906-1415`; её первоначальная композиция сохранена через git stash и `/opt/warland-build/vanilla-release-preserved-initial.patch`. Теперь HEAD exact a2e725e.
- Под общим build.lock запущены exact clean test build, полный Python suite и native smoke. Driver `/opt/warland-build/vanilla-release-build-v2.{pid,exit}`, java/python-v2 и smoke-v1 журналы. Не запускать другой тяжёлый job до проверки exit/PID/lock.
- Smoke output `/opt/warland-build/vanilla-dialog-qa-v1`, source fixture `/opt/warland-build/release-qa/final-runtime` (synthetic, без игроков). Исходную fixture не менять. Новые повторы — в новых каталогах.

## Что дальше
1. Прочитать exact build и smoke результаты; исправить найденные протокольные проблемы. Ошибка/таймаут не успех.
2. Закончить и протестировать auto-bootstrap server: Java21 при отсутствии, Fabric launcher, API, Polymer, WarLand и pack по HTTPS+SHA256. Пины уже получены; рабочий draft `/data/warland-work/bootstrap_server.py` может исчезнуть при сбросе sandbox, его ещё нужно сохранить на GitHub. Не публиковать секреты. Не перезаписывать существующие серверы.
3. Настоящий официальный vanilla client без Fabric/модов: registration → pack consent/download/render → PLAY, меню/предметы/balance, wrong password и reconnect. Cached Mojang manifest+client/assets находятся в `/opt/warland-build/.gradle/caches/fabric-loom`; GUI/Xvfb/xdotool установлены.
4. Release workflow alpha.3, immutable assets/checksums; приватный backup действующей alpha с проверкой восстановления, осторожный deployment и rollback. Никаких wipe/UUID migration/новых owner по нику.
5. Обновить инструкции входа, HANDOFF, ALPHA_PROGRESS и этот файл реальными результатами; освободить свои locks.

## Важные ограничения
Minecraft вправе попросить принять серверный ресурспак при первом входе, далее скачивает/кэширует автоматически. Игроку не нужен Fabric/WarLand-мод. Нативный dialog не маскирует пароль и не проверяет pre-shared server pin: отдельный пароль, не показывать ввод; native RSA/AES-CFB8 не равен TLS/AEAD. Существующие ключ/authDB сохранять.

Market/inventory crash-safety, покупки/захваты, авиация/final HUD, полная moderation/recovery и beta10/20/30 остаются незавершёнными. Не включать их flags ради объявления релиза. Полные данные предыдущего релиза — ALPHA_PROGRESS.md; текущий live server `201.51.10.116:25565`. Миры, backups, ключи и старые dirty trees не удалять. После окончания диалога разработка автоматически не продолжается.
