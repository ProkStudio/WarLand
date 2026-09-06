# WarLand alpha.3.3 — результат и блокер установки

**2026-09-06, после 22:26 МСК. Prerelease опубликована; production НЕ изменён и остаётся alpha.3.2. Установка3.3 заблокирована RTP-приёмкой. Все собственные QA завершились; активного фонового прогона нет.**

## Реализация и исходники
- `/wladmin` вместо старого root-GUI показывает чат; `/wladmin help [1–7]`,27 записей по4 на страницу, до11 сообщений. Назначение, синтаксис, ограничения, пример. Read-only, без выполнения примеров/выдачи прав/чтения секретов.
- Сохранены корневой guard и старые подкоманды; новая справка повторно проверяет текущую owner-сессию. Отсутствующие/недоступные узлы фильтруются. Ник/OP не подменяют OWNER. Опасный purchase gate явно экспериментальный, пример=false. Другой существующий `/staff` UI не заменён.
- PR43 merged `cdf4a489a7f14bf07312d251777846e7348810a8`; exact feature head `1b810bf3a63fc5bb8419d3d368bdd770ea2aa721`. CI34053716955 SUCCESS19:07:13UTC.
- Java525 detected/521passed/4 прежних registry skips/0failures/errors;12 новых тестов. Release Python210passed. Первый ошибочный discovery вtools запустил0tests/exit5: сохранён, не считается PASS; правильный каталог tools/tests.

## Опубликованный релиз
- [v0.1.0-alpha.3.3](https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.3.3),19:11:39UTC; серверныйpatch, не full installer/stable.
- Source/lightweight tag `512ec576c6496b82ad7c1e1f45d6982547bb8aa2`; executable SHA256 `9819f516697e4df1c2ef340dc97f0a343fe9cffa1682dfc00519d5013a389451`.
- CI[34053974031](https://github.com/ProkStudio/WarLand/actions/runs/34053974031): build+publish SUCCESS. Проверены5 ожидаемых assets/sizes,4checksums,SOURCE_COMMIT/tag,metadata3.3,CRC,RTP/helpclasses. Не ориентироваться на release target_commitish=main вместо фактическогоtag.
- Platform immutable=false; workflow отказывается заменять существующий tag/release. Старые версии не переписывались.

## Что действительно прошло
Development JAR(metadata3.1) `65f073efff523338c94f955d812bbfd298adc3305a933232e676d1006fcdfb7d` прошёл отдельную исходную QA. **Затем скачанный точный3.3 JAR отдельно прошёл fresh owner-help suite**, help-published-v1.exit0:
- Обычному авторизованному игроку явно отказано в справке владельца.
- После stop единственный синтетический аккаунт назначен только в ранее незанятую owner-строку собственной приватной QA-базы. Это seed для проверки роли, НЕ enrollment и НЕ реальный владелец.
- Restart, неверный пароль отклонён, верный вход, все7 страниц/предупреждения, неверная страница отклонена, прежний `/wladmin diag` работает.
- Help не меняет owner/accounts/ledger/moderation. Стабильный ключ, один аккаунт, одна стартовая выплата1500, баланс1500, SQLite/FK clean; оба shutdown0, errors=[], password-canaries отсутствуют.
- Это native-wire QA, НЕ графическая/внешняя/beta/load-приёмка.

## Что НЕ прошло — evidence не удалять
1. Exact3.3 RTP v1: FAILED, `ValueError: Packet budget exceeded`, cleanup_exit0. Унаследованный auth-wire бюджет20000 пакетов был общим на длинную сессию; положительная RTP-приёмка не установлена.
2. Exact3.3 RTP v2 с диагностикой: FAILED, `Three bounded cold-load refusals; positive RTP acceptance NOT established`, cleanup_exit0. Получены3 cold-load отказа после27.161/17.855/15.136сек суммарной работы запросов; между ними31.039сек backoff. Проверки каждого отказа подтвердили отсутствие перемещения/записи cooldown; итоговых cooldown rows0, SQLite quick_check=ok.
3. Диагностика:26200 пакетов,2855129 body bytes; явные лимиты20000 на фазу,60000 на соединение,64MiB body bytes. Снятие старого общего ограничения не превратило отказ в PASS, а позволило увидеть конечную причину. Серверные4сек на загрузку чанка/30сек поиска,30сек backoff и максимум3 попытки НЕ изменены.
4.10 RTP class-файлов byte-identical между установленной3.2 и опубликованной3.3. Это не доказательство изменения RTP-кода справкой. Нужна отдельная диагностика холодной генерации/окружения и пригодности точек, а не бесконечные случайные перезапуски до успеха.

QA-only PR44 merged `76cd43936f6ee9f369d0fc723bce20e35c5b5de1`; helper head `2bf54e17139a25295bc50338ba0373b966231c96`,8 новых тестов, full Python218passed15.027s, CI34054731189 SUCCESS19:25:52UTC. Этот helper **не входит в опубликованный tag512ec576** и не меняет Java/JAR. Мерж инструментов диагностики не разрешает установку.

## Пути для продолжения
- Own checkout `/opt/warland-build/owner-help-20260906-1858`, теперь branch `agent/release-20260906-1806/rtp-observed-qa` at2bf54e. Текущий dev-integration head76cd439.
- `logs/build-v1.exit=0`, `python-v1.exit=5`(не тот каталог), `python-v2.exit=0`, `python-v3.exit=0`, `owner-runtime-v1.exit=0`, `help-published-v1.exit=0`, `rtp-published-v1.exit=1`, `rtp-published-v2.exit=1`, `published-driver.exit=1`.
- `logs/published/`: скачанные assets и verified.json. Не перезапускать downloader: existingdirectory намеренно блокируется.
- PASS: `/opt/warland-build/vanilla-dialog-qa-owner-help-published-1858-v1/result.json`.
- FAIL: `/opt/warland-build/vanilla-dialog-qa-help-rtp-published-1858-v1/result.json` и `...-v2/result.json`.
- v2: `rtp-wire-metrics.json`, дополнительно `logs/rtp-v2-metrics-*.json`. Только счётчики/типы/времена, без payloads. Текущий основной metrics-файл перезаписывается следующим соединением; для будущих успешных двухцикловых прогонов добавить сохранение по соединениям. Здесь сбой вcycle1, полный его trace сохранён.
- Local authored `/data/owner-help/`: Java,tests,helpers,notes,workflow,preparers. Старая single-branch git tracking-конфигурация не приняла новую remoteветку: switch был проверен, создан собственный no-track branch от точного2bf54e; файлы не сбрасывались.

## Production и следующий шаг
Сервис3.2 active/running с18:32:16UTC,live hashac515284,в последней проверке0игроков. Новых QA/build JVM нет; OS build/backup locks свободны. Owner/accounts/ключи/миры/flags не менялись help-этапом.

`/opt/warland-ops/owner-help-alpha33-20260906/deploy.py` подготовлен, **НЕ запускался**, result.json отсутствует; SHA256 `6d75dd83b822fd14893a9fd8b63ac721bdbedb3c35060973e566989667226c30`. Он намеренно требует успешные RTP/help evidence и отказывает на текущем failedv1. Не обходить эти проверки и не указывать ему отрицательный v2 как PASS.

Далее: измерить стадии chunk-ticket/генерации и отбор поверхности в отдельном fixture; сравнить ограниченный QA(1CPU/640MiB) с контролируемыми ресурсами, не меняя production. Сохранить safety и конечные бюджеты. Если меняется Java — новая версия/новый tag, не перезапись3.3. Перед установкой обязательны реальный успешный RTP, repeat/restart cooldown, auth/help/data checks точного скачанного JAR, затем отдельный deployment LOCK,0игроков,private backup/readback,code-only swap/postchecks/rollback без отката новых данных.

Открыты также auth-chat/изолированное лобби,lobby каждый вход,крепость160×160,TAB[OWNER]/sidebar с деньгами,market/inventory crash-safety,gameplay,beta10/20/30/stable#15. Не публиковать секреты/БД/миры/архивы, не чистить чужие worktrees и не обещать фоновую разработку после чата.
