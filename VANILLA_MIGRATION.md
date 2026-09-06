# WarLand — переход на обычный Minecraft (в работе)

Запрос пользователя 6сентября2026: привычное подключение без установки Fabric/WarLand/лаунчера. Предыдущая alpha.2 остаётся доступна, но её обязательный companion НЕ считается выполнением нового запроса.

## Выбранный путь
- Сохраняем серверное Fabric-ядро и игровые данные; игроку Fabric не нужен.
- Используем ВСТРОЕННЫЕ в Minecraft1.21.11 Dialog/CustomClickAction packets для формы регистрации в CONFIGURATION, до появления игрока в мире. Не открываем обход /login/командами и не снимаем барьер READY.
- Серверные custom items переводим через Polymer0.15.2+1.21.11 в понятные vanilla предметы + ресурс-пак.
- Сохраняем парольный движок, PBKDF2/лимиты/connection nonce, резерв владельца и одноразовый proof. Пароль не переносим в chat/command history.

## Важные ограничения нового интерфейса
Vanilla TextInputControl не маскирует ввод: требуется отдельный пароль только для WarLand, нельзя показывать форму на стриме. Нативный Minecraft не проверяет наш pre-shared server pin без мода; сохраняем native RSA/AES transport, но это НЕ эквивалент прежней pin-гарантии и не TLS/AEAD. Не выдавать режим за криптографически идентичный companion. Owner остаётся под приватным proof, не просто по нику.

## Состояние на 13:49 UTC
- Создана remote feature/vanilla-client-alpha от release7a038011a8a292b8fdb22b4becc8f4adfbc87668.
- Рабочая копия `/opt/warland-build/vanilla-alpha-20260906`; именно там подготовлены изменения, **ещё НЕ опубликованы и НЕ развёрнуты**.
- NativeAuthDialog: фиксированные action IDs, строгая четырёхполевая NBT-схема/типы/лимиты, nonce, без исполнения команд; в AuthRuntime отдельный opt-in `warland.vanillaClient`, сохранены старый companion и все проверки admission/release. Очередь dialog ограничена флагом на соединение, KDF-лимиты сохранены.
- GunItem и magazine переведены в SimplePolymerItem; версия alpha.3; dependency polymer-core через maven.nucleoid.xyz.
- Запущен clean test build, пока результат НЕ прочитан. `/opt/warland-build/vanilla-alpha-build.{log,exit,pid}`. Build держит общий flock, не запускать параллельную тяжёлую сборку.
- Polymer bundled скачан с Modrinth по HTTPS, SHA512 проверен: 9c205ab398c324ee4dc376269d8aa5df64d11766b6418952a64d2df94f096e665f63eae0c4f0c66e22d03c6ff6767550d1777c28485340131e6556091199062a.
- Подготовлен ресурс-пак из текущих assets, формат75.0; `/var/www/warland-public/packs/warland-assets-alpha.2.zip`, SHA1 fca915c314b8d415892e52d432a64dffb5b421cf. Nginx отдельный static listener80, только /packs/ и /downloads/, без directory listing/админ-путей; firewall80 открыт. **Server.properties resource-pack ещё не переключён.**

## Дальше
1. Проверить build, добавить строгие NativeAuthDialog regression tests и повторить тесты.
2. Новый изолированный runtime с native-dialog flags + Polymer, никакого production cutover до smoke.
3. Настоящий vanilla клиент БЕЗ Fabric/модов: официальный minecraft-client.jar из cached Mojang manifest, только штатные libraries/Java21; проверить registration→resource-pack→PLAY, items/menu/balance, wrong password/reconnect, сохранение UUID/кошельков. Не подменять этим запуск modded runClient.
4. Опубликовать точные исходники/тесты, сохранить резервную копию рабочего alpha-runtime, установить проверенную alpha.3+Polymer, обновить ресурс-пак и повторить публичный vanilla вход.
5. Обновить ALPHA_PROGRESS/HANDOFF и объяснить пользователю обычное подключение. Старый owner-code уже истекает 16:46:57МСК: при необходимости новый выдавать приватно, никогда не публиковать.

Не терять предыдущую alpha/ключ/authDB. Не включать непроверенные рынок/покупки/захваты. Старые dirty worktrees и Paper backup сохранять. Разработка не продолжается автоматически после закрытия диалога.
