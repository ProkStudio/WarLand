# WarLand — актуальное состояние и точка продолжения

Обновлено 2026-09-06, после 23:35 МСК. Агент `release-20260906-2004`, координация #12, задача #45.

**Alpha.3.3 установлена на рабочий сервер. Развёртывание завершено успешно; полный стабильный релиз #15 ещё не готов.** Эта запись сохраняет выполненное и следующий шаг, но не означает фоновую разработку после окончания диалога.

## Что работает сейчас
- Сервис `warland-alpha.service`, `/opt/warland-alpha/runtime`, пользователь `warland`; active/running, WarLand ready в 20:34:40 UTC.
- Установлен точный опубликованный `warland-0.1.0-alpha.3.3.jar`, SHA256 `9819f516697e4df1c2ef340dc97f0a343fe9cffa1682dfc00519d5013a389451`.
- [Релиз alpha.3.3](https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.3.3), фактический tag/source `512ec576c6496b82ad7c1e1f45d6982547bb8aa2`. Релиз существовал раньше; сейчас его проверили и установили, а не переписали tag/assets.
- Обычный Minecraft Java 1.21.11; текущий вход через нативную форму, ресурсы скачиваются автоматически. Адрес из опубликованного плана: `201.51.10.116:25565`. В этой итерации проверялся loopback, не внешняя доступность/GUI.
- На сервере теперь доступна чат-справка `/wladmin` и `/wladmin help [1–7]`: 27 записей с примерами и ограничениями. Права владельца не выдавались и не менялись; ник/OP не подменяют авторизованного OWNER.

## Что сделано с кодом и проверками
- Исправлено перезаписывание отчёта первого RTP-соединения при повторном входе. [PR46](https://github.com/ProkStudio/WarLand/pull/46) merged `7a114ade57578b2095da128ecce498c560f76b3b` в `agent/lobby-20260906-1722/fortress-chat-auth`.
- Exact head `7a3a0a469816d3618d0b3aecfac1a27e10b3f3ab`; [CI34057365967](https://github.com/ProkStudio/WarLand/actions/runs/34057365967) SUCCESS. Python 229/229, включая 11 новых тестов. Отдельный локальный тест: 32 отчёта, 4 параллельных процесса, 128 атомарных записей — PASS.
- Новый `tools/rtp_metrics.py`: отдельный private0600 отчёт на соединение, атомарные обновления фаз, fsync, сохранён latest-отчёт для совместимости. Пароли, packet bodies и идентификаторы игроков не добавлялись. Прежние лимиты пакетов/времени/попыток не менялись.
- Main по-прежнему docs-first, не актуальный игровой исходник. Продолжать игровую разработку от интеграционной ветки выше; не выдавать main за source опубликованного JAR.

## Диагностика RTP — что установлено и чего не утверждать
1. В отдельной копии добавлены только диагностические измерения: polls, load time, tick gap, причины отказа поверхности. Java clean test build PASS: 525 detected, 521 passed, 4 прежних skips, 0 failures/errors; JAR `a7c411ebc6a8f97519f6a61754d4d4a0bb623826489634cf1daf53309c1d6e01`.
2. Этот trace-only Java patch НЕ входит в PR46, релиз или production. Mapped Minecraft1.21.11 подтвердил FULL ticket radius0, неблокирующий getOrNull и правильный heightmap+1.
3. Trace runtime 1CPU/640MiB PASS: пять загрузок чанков 3468/2319/2302/2774/2881ms; первые 16 поверхностей отвергнуты FLOOR/HAZARD/HEADROOM, затем безопасный RTP. Cold-load отказов 0. Два restart/auth/cooldown цикла прошли, shutdown0/errors=[], одна synthetic выплата1500, SQLite/FK clean.
4. Затем отдельно прошёл **точный опубликованный alpha.3.3** на явно указанном профиле 2CPU/1024MiB; production использует 2CPU/1400MiB. Был **один безопасный cold-load отказ**, без перемещения/записи cooldown; после штатного backoff следующая попытка успешна. Повтор и cooldown после restart отклонены, неправильный пароль отклонён, identity/account/wallet сохранены. Оба shutdown0/errors=[].
5. Это подтверждает конкретный alpha acceptance gate, но НЕ исправляет полностью cold-loading, НЕ доказывает нагрузочную/графическую приёмку и НЕ заменяет неуспешные старые прогоны. Startup lag и старые отказы сохранены. Лимиты 4s загрузки/30s поиска, безопасность поверхности и число попыток НЕ ослаблялись.

## Развёртывание и резервная копия
- Driver `/opt/warland-ops/release-alpha33-20260906-2004/deploy.py`, SHA256 `9ce4f827bce106e980fe22dbcc2bda7debacf79790bddf2eb70905c7745645c4`.
- `result.json`: success=true, phase=complete, rollback_used=false; `deploy.exit=0`. Проверены unit binding, online0, старый JAR и точные successful RTP/help evidence.
- До замены создан полный приватный архив, выполнено tar readback-сравнение со штатно остановленным runtime. `/var/backups/warland-owner-help-alpha33-20260906-203405/runtime.tar.gz`, mode0600, SHA256 `d798488776f6b04b1f24e22acbf3642ee7443607f3d243e4c74430559f8d098c`. После установки SHA проверена ещё раз. Booted restore именно этого архива НЕ заявляется.
- Проверены сохранение server identity и baseline profiles/auth/accounts/ledger/owner; startup_errors=[], recoverable native dialog/cancel PASS, read-only private-console help PASS. Новых реальных аккаунтов/прав не создавали.
- Старый JAR сохранён: `runtime/retired-owner-help-alpha33-20260906-203405/warland-0.1.0-alpha.3.2.jar`, SHA256 `ac5152842d6b1f7ac240474771333672fb2d31f4e3f4a09255b90592de429a99`.
- Откат только кода: сначала сервисная проверка/новая резервная копия и graceful stop, сохранить текущий JAR, вернуть старый, запустить/проверить. Не восстанавливать старую БД/мир поверх новых действий игроков. Старый код лишает новой help-справки.

## Сохранённые результаты и ошибки
- Checkout `/opt/warland-build/rtp-cold-20260906-2004`; `logs/trace-source.patch`, `logs/trace-build.{log,exit}`, `logs/python-metrics.{log,exit}`. Старые/чужие dirty trees не менялись.
- Trace PASS: `/opt/warland-build/vanilla-dialog-qa-rtp-trace-2004-v1/result.json`.
- Exact published PASS: `/opt/warland-build/vanilla-dialog-qa-rtp-published-2004-v2/result.json`; `logs/published-runtime-v2.exit=0`. В каждом двухцикловом прогоне сохранены два отдельных connection reports.
- Новый published v1 FAILED до старта сервера: EADDRINUSE на повторно используемых портах25591/18101; output не создавался. Логи сохранены. V2 использовал отдельно проверенные25592/18102.
- Ранее HTTP429 блокировал подключение. После исправления пользователем прочитано фактическое состояние; неизвестный запуск не дублировался. Все собственные QA/deployment jobs завершились; на финальной проверке только рабочая JVM, общие build/backup locks свободны.
- Старые failed help/RTP результаты и старый заблокированный driver сохранены. Полный исторический отчёт до этой итерации доступен в истории `OWNER_HELP_STATUS.md`.

## Следующий шаг
- Продолжить #45: воспроизводимость/производительность холодной генерации RTP. Не считать один PASS устранением всех отказов; изменения Java — новая версия и точная повторная приёмка, не перезапись3.3.
- Далее по согласованному PLAYER_EXPERIENCE_STATUS: безопасный auth-chat/изолированное lobby, lobby после каждого входа, крепость160×160, TAB[OWNER] и sidebar с деньгами; затем inventory/market crash-safety, игровые механики и beta10/20/30. Перед правками прочитать свежие #12/CLAIM/ветки, не присваивать чужую работу.
- Стабильный релиз #15 остаётся открытым. Не включать незавершённые market/capture/purchase flags ради демонстрации.
- Не публиковать пароли, identity.bin, БД, миры, реальные UUID или архивы. Сохранять промежуточные результаты в GitHub и освобождать только собственные ресурсы после проверки.
