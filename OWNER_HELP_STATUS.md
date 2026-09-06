# WarLand alpha.3.3 — установлена на сервер

**2026-09-06, после 23:35 МСК: alpha.3.3 развёрнута успешно.** Прежний блокер установки снят новой положительной проверкой точного опубликованного файла; старые неуспешные прогоны сохранены. Полный stable-релиз не готов.

## Для игрока и владельца
- `/wladmin` и `/wladmin help [1–7]` теперь дают чат-справку по 27 командам с примерами и ограничениями.
- Авторизация и проверка текущей owner-сессии сохранены. Реального владельца, аккаунты и права не меняли.
- Обычный Minecraft Java1.21.11, прежняя нативная форма входа и автоматический ресурс-пак. Запрошенный auth-chat/lobby/HUD ещё не реализован этой итерацией.

## Подтверждение установки
- Live JAR SHA256 `9819f516697e4df1c2ef340dc97f0a343fe9cffa1682dfc00519d5013a389451`, tag/source `512ec576c6496b82ad7c1e1f45d6982547bb8aa2`.
- `warland-alpha.service` active/running, ready20:34:40UTC; `deploy.exit=0`, success=true, rollback_used=false.
- Приватный полный backup/readback выполнен; identity/baseline accounts/ledger/owner сохранены; ошибок запуска нет. Recoverable native dialog/cancel и private-console `/wladmin help 7` прошли на live.
- Driver и отчёт: `/opt/warland-ops/release-alpha33-20260906-2004/`.
- Новая RTP-приёмка exact JAR: `/opt/warland-build/vanilla-dialog-qa-rtp-published-2004-v2/result.json`, 2CPU/1024MiB, positive teleport/repeat/restart/auth/data checks PASS. Один cold-load отказ до успешной второй попытки сохранён; лимиты и безопасность не ослаблялись. Это не доказательство безотказности или beta/load.
- Предыдущая отдельная owner-help suite того же JAR остаётся PASS: `/opt/warland-build/vanilla-dialog-qa-owner-help-published-1858-v1/result.json`.

## Актуальная точка продолжения
Подробные результаты, исходники, ошибки, checksum резервной копии, безопасный откат и следующий шаг: [AUTONOMOUS_PROGRESS.md](AUTONOMOUS_PROGRESS.md).

PR46 merged7a114ade: отчёты RTP теперь сохраняются отдельно по соединениям; fullPython229PASS, CI34057365967SUCCESS. Это QA-код, не изменение опубликованного JAR.

Исторические failed packet-budget/cold-load прогоны и старый blocked driver НЕ удалялись и НЕ переименованы в успешные. [Полный отчёт до установки](https://github.com/ProkStudio/WarLand/blob/e1baa91f574def63d650f222f297893f1041deb8/OWNER_HELP_STATUS.md) сохранён в Git. Новая установка использовала новый driver и новые успешные evidence, а не обход failed gates.
