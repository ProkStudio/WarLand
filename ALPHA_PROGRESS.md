# WarLand — журнал запуска игровой альфы

Обновлено 2026-09-06 после 16:11 МСК. Работа не продолжается автоматически после закрытия диалога.

## Цель
Открыть ограниченную игровую альфу с клиентским комплектом. Пользователь разрешил замену старого сервера; его данные сохраняются для отката. Полное ТЗ/стабильный релиз не объявлены завершёнными.

## Готово и проверено
- Исходники игры: `2a89fa966f0f3fabf90d785fc22aaebb52655db1`, ветка `release/0.1.0-alpha.2`. Версия при сборке `-Pmod_version=0.1.0-alpha.2`.
- VPS clean test build SUCCESS: JUnit 442 обнаружены, 438 успешны, 4 прежних skips, 0 failures/errors. Python 156/156.
- VPS JAR SHA256: `a9da62dbf075f3324370547562c175c266dc88f6e763eb1da70f84b4149aa64f`.
- Свежая native encrypted-offline матрица успешна: register→PLAY и balance; неправильный пароль без PLAY/изменения данных; полный restart→login; один профиль/аккаунт/стартовая выдача 1500, SQLite quick_check/FK OK; два штатных выхода, ERROR/Exception нет.
- Шесть повторных входов не вытеснили играющего пользователя. Отложенный LOGIN acknowledgment отклонён, действующий клиент продолжил получать balance. Детерминированное удержание интервала onKey→tickVerify отдельно не выполнено.
- Результат `/opt/warland-build/solo-alpha-native-qa-v1/result.json`, SHA256 `10430b4573500c32285f8b62a57499bded4482ac16a8953ba91958095bf975e8`. Только синтетические тестовые данные; нет authlib/Mojang overrides.
- Paper штатно остановлен. Полный архив `/var/backups/warland-cutover-20260906/paper-server.tar` сравнён с остановленным источником; SHA256 `7295c6390582e37e3af0eb87cb991b781bcfab22904b2ee00774d75f2831f41b`. Исходный `/opt/minecraft/server` не удалён. Старый staging также остановлен ради памяти.
- Новый `warland-alpha.service` запущен с отдельным свежим миром на **loopback 25579**, не публично. Runtime `/opt/warland-alpha/runtime`; root-only app, JVM под warland, UNIX console `/run/warland-alpha/console.sock`, no RCON/query. Guard не отключён, используется явный encryptedOffline.
- Новый публичный fingerprint: `f7c01003a649b67b97791091e687c9ee3ac275affc15dcf585a2f34df2efac8f`.
- Опубликованы manifest, инструкции и release pipeline: `7a038011a8a292b8fdb22b4becc8f4adfbc87668`. Pipeline https://github.com/ProkStudio/WarLand/actions/runs/34035372869 повторяет тесты, собирает клиент ZIP с pin и публикует prerelease. Пока результат не прочитан.

## Сейчас / продолжить
1. GUI-проверка настоящего Fabric companion через Xvfb. Первый запуск упал из-за X11 authorization/GLFW (не игровой код); настроен отдельный Xauthority, второй запущен. Логи `/opt/warland-build/solo-alpha-client-v2.log`, DISPLAY=:96, Xauthority `/opt/warland-build/solo-alpha.xauth`. Checkout `/opt/warland-build/solo-alpha-20260906`; клиент ограничен timeout 900s и держит build.lock. Не запускать параллельную тяжёлую сборку. Клиентский nickname синтетический `WlVisualAlphaQA`.
2. Проверить GUI register/login и сообщения; не выдавать headless protocol за GUI-приёмку.
3. После успешной проверки остановить alpha, сохранить новый ключ/БД/мир, сменить bind на публичный 25565, перезапустить; проверить стабильность ключа, ответ и внешнюю доступность. В этой точке публичный порт пока не открыт WarLand.
4. Прочитать итог release workflow; проверить ZIP/хеши и при необходимости поставить именно опубликованный бинарник с повторной проверкой.
5. Обновить этот файл и выдать пользователю ссылку на готовый комплект и адрес.

## Откат и ограничения
Если alpha не готова: остановить только warland-alpha, вернуть minecraft.service, сохранив новые alpha данные. Не смешивать Paper/offline UUID, не удалять старые world/DB и чужие dirty worktrees. Приватные архивы/ключи/пароли/owner proof не публиковать.

Скупщик, аукцион/escrow, покупки зданий и захваты выключены. Авиация/final HUD, полноценный рынок/инвентарные crash-регрессии, нагрузочная бета 10/20/30 не завершены. Owner/OP не назначены. Для reserved owner нужен отдельный приватный bootstrap, не один ник. Комплект — Java Edition 1.21.11 / Fabric Loader 0.19.5 / Fabric API 0.141.6+1.21.11 / Java21; установка в docs/ALPHA_JOIN.md release-ветки.
