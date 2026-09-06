# WarLand — действующая игровая альфа и точка продолжения

Обновлено 2026-09-06, после 16:31 МСК. **Ограниченная альфа 0.1.0-alpha.2 опубликована и запущена. Полное ТЗ/стабильный релиз не завершены.** После закрытия диалога работа автоматически не продолжается.

## Вход
- Сервер: **201.51.10.116:25565** (Java Edition).
- [Готовый релиз и клиентский ZIP](https://github.com/ProkStudio/WarLand/releases/tag/v0.1.0-alpha.2).
- [Пошаговая установка](https://github.com/ProkStudio/WarLand/blob/release/0.1.0-alpha.2/docs/ALPHA_JOIN.md).
- Minecraft 1.21.11, Java21, Fabric Loader0.19.5, Fabric API0.141.6+1.21.11. ZIP содержит companion, API и публичный server pin; Minecraft/Java/launcher в нём нет.
- Обычный игрок выбирает постоянный ник, вводит уникальный пароль 12–128 символов дважды и нажимает «Регистрация». Затем — тот же ник/пароль, «Войти». Поле owner-кода обычному игроку не нужно.
- `egorkrid666` зарезервирован для владельца. Owner/OP автоматически по никнейму не выданы. Приватный bootstrap создан 13:31:57 UTC, срок15мин; в GitHub/ZIP его нет. После истечения нужен новый proof через приватную консоль; не снимать защиту ради доступности.

## Подтверждённые проверки
1. Код игры `2a89fa966f0f3fabf90d785fc22aaebb52655db1`; release-коммит `7a038011a8a292b8fdb22b4becc8f4adfbc87668`, тег `v0.1.0-alpha.2`. Build version override `-Pmod_version=0.1.0-alpha.2` (gradle.properties исторически alpha.1).
2. VPS clean test build SUCCESS: JUnit442 detected /438passed /4pre-existing skips /0failures/errors; Python156/156. [Release CI](https://github.com/ProkStudio/WarLand/actions/runs/34035372869) также полностью SUCCESS; опубликованы ZIP/JAR/checksums.
3. Скачанный из релиза ZIP проверен по checksum и ZIP CRC. JAR побайтно совпадает с проверенным VPS build и установленным сервером; сравнение всех ZIP entries не нашло различий.
4. Свежая native encrypted-offline матрица: register→PLAY+balance; неправильный пароль не допускает PLAY/изменений БД; полный restart→login; неизменный ключ; один профиль, аккаунт, кошелёк, ledger/starter1500; SQLite quick_check/FK OK; два server shutdown exit0, без ERROR/Exception. Не использовались тестовые Mojang/authlib overrides.
5. Шесть повторных входов отвергнуты без вытеснения играющего аккаунта. Отложенный LOGIN acknowledgment также отвергнут, incumbent сохранил balance-response. Детерминированное удержание интервала onKey→tickVerify отдельно НЕ проверено.
6. **Настоящий графический Minecraft/Fabric companion** запущен через Gradle runClient/Xvfb на том же исходном коде: показал форму, зарегистрировал синтетический WlVisualAlphaQA, выполнил registry sync и вошёл в мир. Получены welcome/tutorial, открывалось `/warland`, команда `/balance` вернула1500. После штатного disconnect выполнен GUI password-login через публичный IP, снова welcome/PLAY. После этого в БД по-прежнему один starter1500 и целая БД. Это настоящий клиентский smoke, но не установка ZIP человеком на Windows/macOS и не многопользовательская бета.
7. Внешний mcstatus.io подтвердил online=true, Minecraft1.21.11/protocol774, нужный MOTD, порт25565. Прямой TCP probe из отдельного sandbox получил reset; этот путь НЕ считается успешной внешней проверкой. GUI-клиент на VPS входил через публичный IP (hairpin), не из другой сети.
8. Синтетический игрок штатно отключён; сервер остаётся active/enabled. Test client позже завершён ограничителем времени (exit124), не сервер. Пароль тестового аккаунта не обнаружен в server/client logs. Тестовый аккаунт не имеет owner/OP.

## Точные хеши
- JAR/установленный сервер: `a9da62dbf075f3324370547562c175c266dc88f6e763eb1da70f84b4149aa64f`.
- Клиент ZIP: `cda5153660e0565a77c5d949465b44237952582d38753a67a173092a505b4c34`.
- Fabric API: `bdff7fd7e220085cfad2ff9b1f40dde6534ae0b96cf378f97a374bc54cb9ed0f`.
- Публичный fingerprint: `f7c01003a649b67b97791091e687c9ee3ac275affc15dcf585a2f34df2efac8f` (сохранён между рестартами).
- Синтетическая wire matrix JSON: `10430b4573500c32285f8b62a57499bded4482ac16a8953ba91958095bf975e8`.

## Сервер, данные, откат
- Активный/автозапуск: `warland-alpha.service`. `/opt/warland-alpha/runtime`, приложение `/opt/warland-alpha/app`. JVM под `warland`, root-owned tools, UNIX console `/run/warland-alpha/console.sock`, no RCON/query. Xmx1400M;10слотов, view/simulation4. Это лимит, не нагрузочная гарантия.
- `online-mode=false` только с обязательным `-Dwarland.encryptedOffline=true`, native RSA/AES-CFB8 и pre-shared pin; requireOnlineModeForPublic guard не ослаблен. Не TLS/AEAD. `pause-when-empty-seconds=-1` отключает vanilla-паузу пустого dedicated server.
- Скупщик, auction/escrow, захваты и покупки зданий выключены. Создан отдельный новый мир; UUID/экономика Paper не мигрировали.
- Старые minecraft/Paper и warland-staging остановлены и отключены от автозапуска. **Не удалены.** `/opt/minecraft/server` сохранён. Согласие пользователя на замену было получено в запросе.
- `/var/backups/warland-cutover-20260906/paper-server.tar` — полный приватный архив остановленного Paper; tar compare с исходником успешен. SHA256 `7295c6390582e37e3af0eb87cb991b781bcfab22904b2ee00774d75f2831f41b`. Сохранён unit/старый enabled-status. Это проверка архива, не полноценный boot восстановленного Paper.
- `alpha-before-public.tar` в том же приватном каталоге — архив alpha-runtime перед открытием порта, tar compare успешен. Публичного ключа недостаточно для восстановления: приватная identity и auth DB должны сохраняться вместе.
- Откат: штатно остановить warland-alpha; отдельно сохранить НОВЫЕ данные/ключ/БД; восстановить выбранную совместимую alpha-копию либо временно запустить сохранённый minecraft.service. Одновременно два сервиса на25565 не запускать. Не откатывать БД поверх новых пользовательских данных без отдельного решения.

## Где искать доказательства на VPS
`/opt/warland-build/solo-alpha-20260906` — build checkout (не менять поверх чужих процессов); `solo-alpha-{java,python}.log`, `solo-alpha-build.exit`; `solo-alpha-smoke.py`, `solo-alpha-native-qa-v1/result.json`; `solo-alpha-client-v3.log`, `solo-alpha-ui-*.png`, `solo-alpha-gui-register.log`; `alpha-release-download/` — скачанный и проверенный релиз. Предыдущие неуспешные логи сохранены: первый client crash — Xauthority/GLFW; Xvfb v2 исправлен. Virtual-client warnings: отсутствующее audio/narrator устройство, dev-token Realms401 и cursor shape. Они не объявлены игровым тестом звука/лицензионного launcher. При одновременном GUI на VPS были startup/CPU lag warnings; нагрузочная приёмка не проводилась.

## Дальнейшая разработка — не выполнено
- Inventory/ItemStack crash matrix, готовый рынок/скупщик/аукцион и покупки.
- Городские уровни/содержание, расширенная дипломатия, многопользовательские войны.
- Авиация/техника/финальный HUD/ресурспак, события/квестовые цепочки/рейтинги.
- Полные moderation/античит/rollback и реальные беты10/20/30, профилирование/баланс.
- Полный disaster recovery рабочих alpha-данных и графическая проверка пользовательской установки ZIP.

Начинать дальнейшую работу с этого журнала и точной release-ветки. Старые HANDOFF/AGENT_STATUS в feature-ветках описывают прошлые этапы. Не переобъявлять закрытые auth-блокеры и не принимать старые свидетельства за проверку нового бинарника. Не force-push, не удалять чужие dirty trees. Приватные ключи, пароли, bootstrap, миры и базы в публичный GitHub не копировать.
