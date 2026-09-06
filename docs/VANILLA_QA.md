# Vanilla alpha.3 — фактический QA перед публикацией

2026-09-06. Этот отчёт фиксирует проверки candidate; не означает, что production уже обновлён. Фактическое развёртывание — main/PLAY_ALPHA.md и RUN_STATE.md.

## Точный исходный код

Игровой код/инструменты проверены на `522958f5f802b8e01e96f34cd8ef306149e28483`. `5bbc419955470b16d0b47753662d09a508818279` добавляет только publishing workflow и operator/player docs. Этот документ является контрольной записью запуска CI публикации release-ветки; CI повторяет clean build и все unit suites.

- Java: 468 detected, 464 passed, 4 прежних skips, 0 failures/errors.
- Python: 210/210 passed.
- Clean Gradle build: SUCCESS, 1m23s на VPS.
- Локальный исполняемый JAR: SHA256 `8b769de16eb04fb775a10b033891aba100b5fa94e2e91367a8c79f85a012aff6`.
- Pack: 5174 bytes / 7 entries / format75.0; SHA1 `a33b73cc1a627bd73b165cf814a273edfce38bdc`; SHA256 `fd369c0bc4d6ebc584f4dd24ea4c057ee1e7bbbc8116b4c23c8fa884b1002488`.
- Реальные хеши публикуемого комплекта вычисляет CI и фиксирует в SHA256SUMS/server-lock.json; перед deployment скачанный комплект проверяется заново.

## Native wire v3: PASS

Без регистрации Fabric/WarLand client channels. Настоящий native RSA/AES-CFB8 transport; отмена с точной причиной, stale nonce/неизвестный action, регистрация, auth barrier до pack/PLAY, HTTP pack download + SHA1/SHA256/ZIP CRC, balance. Затем штатный stop/start, неверный пароль с redisplay без PLAY, повторный корректный login.

В обоих циклах: нормальный exit0, 0 ERROR/Exception, profiles=1, auth_accounts=1, одна стартовая выплата 1500, неизменные wallet/account aggregates, стабильный private server identity, SQLite quick_check=ok и FK errors=0. Credential canaries в server logs отсутствуют. Первая неуспешная v1 harness попытка сохранена отдельно и не засчитана как acceptance; v2/v3 используют length-prefixed NBT и проверяют точную причину cancel.

## Официальный графический vanilla client

Официальный Minecraft1.21.11 client SHA1 `ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd`; main `net.minecraft.client.main.Main`; без Fabric/WarLand на клиенте. JAR/libraries/natives проверены по Mojang manifest. Регистрация через native форму → штатное согласие на обязательный pack → настоящее скачивание в кэш, reload → PLAY. Визуально просмотрены форма и цветной кадр инвентаря/АК-74/магазина без missing-texture checkerboard. `/balance` из реального GUI вернул 1500; `/warland` открыл контейнер меню.

GUI данные: auth/accounts/profiles/ledger по одному, integrity ok. Повторный GUI connect не завершён: навигация не вышла из титульного экрана, поэтому секреты туда не вводились; wrong-password/restart остаются доказанными wire-сценарием, не GUI. Client завершён ограниченным supervisor (exit143), server штатно exit0, Xvfb exit0; принудительного kill не потребовалось. Все исходные private evidence/data сохранены на VPS, не включены в release.

## Automatic Java

Реально скачан, SHA256-проверен, безопасно распакован и запущен для version probe Temurin21.0.12.1+1; затем подтверждено повторное использование без download. SHA256 архива `2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500`. Только отсутствие system Java моделировалось на этапе поиска executable; download/extract/probe не были mocks.

## Чего этот отчёт не доказывает

Нет внешней пользовательской beta/load аттестации и полной проверки всех игровых функций. GUI работал через software rendering; отмечены ожидаемые offline-test Realms/profile-key auth ошибки, выключенный narrator backend и стартовые lag warnings. Native пароль не маскируется, stock client не проверяет pre-shared pin. Не завершены stable-гейты #15; market/building purchase/capture flags остаются отключены. До cutover обязательны проверка опубликованного installer, приватный backup, восстановление копии и service-specific readiness.
