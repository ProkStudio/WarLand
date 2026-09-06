# Vanilla-клиент и автоматические ресурсы — alpha.3

Работа в процессе; этот коммит сам по себе не является проверенным релизом или развёртыванием.

Minecraft Java 1.21.11 использует встроенный CONFIGURATION dialog: игрок ещё не появился в мире до успешной регистрации/входа. Включается только явным `-Dwarland.vanillaClient=true`; для существующего offline alpha также обязательно `-Dwarland.encryptedOffline=true`. Companion не требуется при включённом vanilla режиме. Серверные предметы переводятся Polymer в vanilla-предметы с item models из серверного ресурспака.

## Границы безопасности
- Действия фиксированы: login/register/cancel; точная четырёхполевая NBT-схема, ограничения длины/типа/символов и nonce соединения. Ни chat, ни команды не используются для паролей.
- Одна queued-dialog action и один password job на соединение; admission, KDF/abuse limits, 120s pre-auth TTL, reserved owner proof, session/profile/lease checks сохранены.
- Vanilla-поля НЕ маскируются. Пароль должен быть отдельным для WarLand; не показывать форму посторонним/на стриме. Vanilla не обеспечивает проверку заранее переданного server pin: native RSA/AES-CFB8 без pin не эквивалентен companion и не TLS/AEAD. NBT/Java строки нельзя гарантированно обнулить; они не должны журналироваться.
- Данные/ключи существующей alpha сохраняются. Нет назначения владельца по нику или включения непроверенной экономики/захвата/покупок.

## Зависимость и лицензия
Polymer, автор Patbox и contributors: https://github.com/Patbox/polymer, версия `0.15.2+1.21.11`; Modrinth project `xGdtZczs`, version `wugBT1fU`. Лицензия **LGPL-3.0-only**. Зависимость остаётся отдельным неизменённым JAR, не копируется в исходники WarLand. Исходники и лицензия доступны в upstream; установщик скачивает публичный upstream binary. Fabric API, Fabric Loader и Minecraft остаются самостоятельными зависимостями. Клиент/Java/Minecraft binaries не включаются в публичный ресурспак.

Polymer bundled URL: https://cdn.modrinth.com/data/xGdtZczs/versions/wugBT1fU/polymer-bundled-0.15.2%2B1.21.11.jar
SHA-256: `2dfc65322e8ac7caed28cdd9a9836516c75fa880494112c03b4ee85ddccf44ed`
SHA-512: `9c205ab398c324ee4dc376269d8aa5df64d11766b6418952a64d2df94f096e665f63eae0c4f0c66e22d03c6ff6767550d1777c28485340131e6556091199062a`

При первом подключении Minecraft вправе запросить согласие на серверный ресурспак; это действие нельзя обходить. После принятия клиент сам скачивает/кэширует пакет. Не предлагать ручную установку модов как выполнение vanilla-требования. Сначала точные unit/integration tests, затем isolated real vanilla-client и resource-pack checks, затем backup/rollback и deployment.
