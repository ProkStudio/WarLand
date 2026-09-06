# Обновление alpha.2 → vanilla alpha.3

Это operator runbook, не безусловный shell-скрипт. Перед командами проверить фактические пути/состояние и координацию #12. Не запускать новый installer поверх действующего сервера.

## До остановки

- Убедиться в успешных CI/source/runtime проверках, сверить immutable release SHA256SUMS и server-lock.json. Не подменять файлы существующего релиза.
- Проверить service unit, действующий JAR, Minecraft/Java21, свободное место, список модов и число подключений. Предупредить игроков о коротком техническом перерыве, если они онлайн.
- Подготовить новый JAR, Polymer bundled и HTTPS pack отдельно от активного runtime; проверить хеши. Не подключать незавершённые market/capture/building flags.
- Рабочие пути этой установки: `/opt/warland-alpha/runtime`, приложение `/opt/warland-alpha/app`, unit `warland-alpha.service`. Не трогать сохранённые Paper/staging сервисы и исходные dirty worktrees.

## Резервная копия и проверка восстановления

1. Остановить ТОЛЬКО `warland-alpha.service` штатным `systemctl stop`; дождаться нормального сохранения миров и отсутствия процесса/игрового listener. Не использовать `kill -9` как обычный способ остановки.
2. Создать новый приватный каталог backup (0700), сохранить весь `/opt/warland-alpha`, service unit/drop-ins и относящиеся конфигурации с owners/permissions. Миры, SQLite, authDB и private server identity — единый комплект; не копировать открытый SQLite как произвольные отдельные файлы.
3. Посчитать SHA256 архива, сверить архив с остановленным деревом. Извлечь в НОВЫЙ приватный restore-каталог, сверить состав/хеши, выполнить SQLite quick_check и foreign_key_check.
4. Для boot rehearsal использовать отдельный owned runtime с копией данных, loopback IP/свободный другой порт, выключенными RCON/query и ограниченным процессом. Не запускать две копии одного live runtime.
5. Никогда не загружать archive, private keys, owner proof, пароли, playerdata или runtime logs в GitHub/публичные downloads. Старые backups не удалять.

## Замена приложения

- Сохранить старый WarLand JAR вне `runtime/mods`; Fabric не должен одновременно увидеть два JAR с id warland. Поставить проверенный alpha.3 JAR и Polymer bundled. Совместимые Fabric launcher/API сохранить или сверить с manifest.
- В прежнем ExecStart сохранить параметры/защиту unit и `-Dwarland.encryptedOffline=true`, добавить `-Dwarland.vanillaClient=true`. Не менять service user, приватную консоль или firewall для администрирования.
- В server.properties настроить immutable HTTPS `resource-pack`, точный `resource-pack-sha1`, стабильный `resource-pack-id`, `require-resource-pack=true`. Оставить offline-account-compatible режим, отсутствие whitelist, выключенные RCON/query, enforce-secure-profile=false и существующие безопасные лимиты.
- Не удалять/переименовывать UUID аккаунтов, миры, authDB или identity. Не выдавать owner/OP по никнейму и не отключать pre-auth gate.
- `systemctl daemon-reload`, затем `systemctl start warland-alpha.service`. Проверить active/ready, версию JAR/набор модов, порт, отсутствие новых runtime ошибок, сохранение identity и инварианты базы.

## Откат

Если candidate не проходит readiness/проверки: штатно остановить именно этот сервис; отдельно сохранить НОВОЕ состояние после попытки обновления; вернуть старые JAR/unit/properties из приватной копии. При совместимой схеме сохранять актуальные player/world данные. Возвращать весь старый архив без разбора нельзя — это может стереть игру после обновления. Полный restore требует отдельного решения с учётом новых данных.

Не объявлять внешнюю beta или нагрузочную готовность на основании localhost-проверки. Записать реальные версии, hashes, backup/restore результаты и ограничения в RUN_STATE/HANDOFF/ALPHA_PROGRESS. После завершения снять собственный coordination LOCK.
