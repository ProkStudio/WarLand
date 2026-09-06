# WarLand — автономное продолжение 2026-09-06 23:04 МСК

Статус: диагностика RTP начата; новый релиз/развёртывание этой итерацией ещё не выполнены. Полный stable #15 не готов. Файл обновляется по выполненным этапам; после окончания диалога фоновая работа не подразумевается.

## Актуальная отправная точка
- Репозиторий ProkStudio/WarLand. main содержит документы, не актуальный игровой исходник.
- Игровая интеграция: `agent/lobby-20260906-1722/fortress-chat-auth`, commit `76cd43936f6ee9f369d0fc723bce20e35c5b5de1`.
- Рабочий сервер: alpha.3.2, service `warland-alpha`, runtime `/opt/warland-alpha/runtime`, непривилегированный warland. Проверен active/running, только рабочая JVM. Данные/owner/ключи/миры не менялись.
- alpha.3.3 опубликована (tag512ec576, JAR9819f516), owner-chat help принят в isolated suite, установка заблокирована RTP: три ограниченных отказа cold-load. Предыдущий production3.2 и новый3.3 RTP byte-identical; причина ещё не установлена.
- Предыдущие точные проверки/ошибки: OWNER_HELP_STATUS.md и PLAYER_EXPERIENCE_STATUS.md. RUN_STATE.md старее их, не использовать его alpha.3 как текущую production.

## Текущая работа
Agent release-20260906-2004, координация #12. Новая область: RTP cold-generation diagnosis, узкое исправление при доказанном дефекте, регрессионные тесты. Только собственный новый checkout; прежние dirty trees/evidence не изменять. Другие auth-chat/HUD/content/market задачи не присваиваются.

## Последовательность
1. Прочитать RtpService/Policy, actual mapped Minecraft API и QA; установить причину, а не повышать лимиты до случайного PASS.
2. Сохранить воспроизводимый regression и исправление; exact clean build/Python и fresh nonroot synthetic runtime под build.lock. Положительный RTP, повтор/restart cooldown, auth/help/data checks обязательны.
3. Проверенный код — отдельная ветка/PR/CI; новая версия если меняется Java, старые tags/assets не заменять.
4. До установки: exact downloaded artifact QA, zero-online/service binding, private backup/readback, code-only rollback с сохранением новых player actions. Не обходить failed gates.
5. Обновить этот файл результатами и следующим шагом, освободить собственные locks.

## Безопасность и оставшийся объём
Не публиковать пароли/identity.bin/БД/миры/архивы/реальные player UUID. Не удалять старые артефакты, worlds, accounts, worktrees. Public entry vanilla1.21.11 уже работает через native dialog; auth-chat/lobby/fortress/HUD и безопасная торговля/инвентарь/нагрузка остаются незавершёнными. Не отмечать stable ради завершения отчёта.
