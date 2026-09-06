# WarLand — текущая точка продолжения

Обновлено 2026-09-06, после 18:18 МСК. Работа по #39. Пользователь просит продолжать автономно до релиза, с автоматической загрузкой необходимых ресурсов. Цель этого этапа — vanilla alpha.3; полный stable по ТЗ/#15 НЕ готов.

## Подтверждено на этом этапе
- Обычный официальный Minecraft Java 1.21.11 БЕЗ Fabric/WarLand на клиенте прошёл native registration, получил штатный запрос обязательного ресурспака, скачал/применил его и вошёл в изолированный мир.
- Реальный клиентский кэш: pack 5174 bytes, SHA1 `a33b73cc1a627bd73b165cf814a273edfce38bdc`, SHA256 `fd369c0bc4d6ebc584f4dd24ea4c057ee1e7bbbc8116b4c23c8fa884b1002488`.
- Визуально просмотрены пустая форма входа и цветной игровой скриншот: русский текст, штатный инвентарь, модель АК-74 и магазина без missing-texture checkerboard. `/balance` через настоящий GUI вернул 1500; `/warland` открыл контейнер меню. Это не полная визуальная проверка всех игровых функций.
- GUI fixture: auth_accounts=1, profiles=1, accounts=1, ledger=1; SQLite quick_check=ok. GUI reconnect/wrong-password сценарий НЕ завершён: автоматизация не перешла с титульного экрана к повторному соединению и не вводила туда пароль. Эти отрицательные/повторные сценарии отдельно прошли native wire QA.
- GUI supervisor завершён: client exit143 при ограниченном по времени завершении, server exit0, Xvfb exit0, без принудительного kill. Миры сохранены. Только production Java осталась работать; alpha.2 active и этой сессией не обновлена.

## Код и проверки
Ветка `agent/release-20260906-1403/vanilla-autodownload`, опубликованный checkpoint `7c6f61d327a92e7788a9d643f60dcbfd13dd12e7`.
- Native auth: `b539898`; Polymer/version/docs: `59b9cf4`; deterministic pack: `92be504`; native smoke: `a2e725e`; installer/pins: `9a12179`; installer tests: `7c6f61d`.
- Exact game source a2e725e: Java464 detected /460passed /4existing skips /0failures/errors; Python172/172. JAR SHA256 `8b769de16eb04fb775a10b033891aba100b5fa94e2e91367a8c79f85a012aff6`.
- Первый native smoke v1 провалился из-за тестового кодека (length-prefixed NBT, не presence byte). Исправлены ТОЛЬКО две строки harness: `base.blob(nbt)` и проверка точного текста отмены. Исправления пока в VPS working tree, нужно опубликовать их до sync.
- Native smoke v2 PASS: encrypted native transport без mod channels; cancel, stale nonce/unknown action, registration, pack hash+CRC, PLAY/balance, graceful restart, wrong password, повторный login, одна стартовая выплата 1500, стабильный server identity, SQLite integrity/FK, отсутствие credential canaries в server logs.
- Локальные новые Python тесты: 24 installer +16 pack +14 release packager =54/54. Последние 14 и package_server.py ещё локальные, НЕ опубликованы на момент этого checkpoint. Полный suite после новых файлов ещё не прогонялся.

## Где продолжать
- VPS checkout `/opt/warland-build/vanilla-release-20260906-1415`: HEAD a2e725e, FETCH_HEAD 7c6f61d, dirty только две проверенные строки `tools/vanilla_dialog_smoke.py`.
- Native evidence `/opt/warland-build/vanilla-dialog-qa-v2/result.json` и boot-{1,2}.log; v1 сохранить как failed evidence.
- GUI evidence `/opt/warland-build/vanilla-gui-qa-v1`: client-pins.json, launch.json, supervisor-state.json, server/client logs и приватные screenshots. Server data `/opt/warland-build/vanilla-gui-server-v1`. Секретные синтетические password files НЕ публиковать.
- Официальный client main `net.minecraft.client.main.Main`, SHA1 `ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd`; библиотеки/нативы проверены по официальному Mojang manifest. Client logs содержат ожидаемые offline test Realms/profile-key auth errors и отсутствующий выключенный narrator backend; это не нулевая-error desktop/performance аттестация.
- `/data/warland-work/package_server.py` и `test_package_server.py` — проверенные локальные drafts (14 tests). Sandbox-файлы иногда исчезают: публиковать оперативно. Installer и его тесты уже на GitHub.
- Изначальный dirty checkout `/opt/warland-build/vanilla-alpha-20260906` НЕ тронут. Initial composite сохранён stash `preserved-composite-before-exact-source` и `/opt/warland-build/vanilla-release-preserved-initial.patch`.

## Следующие действия до alpha.3
1. Опубликовать исправленный smoke, package_server.py/14 tests, финальные русские installer defaults/docs и отдельный alpha.3 publish workflow.
2. Проверить настоящий auto-download/extract/start Java fallback и новую установку в изоляции. Unit tests с mocks не считать реальной установкой.
3. Синхронизировать точный published source с сохранением своих текущих изменений, clean build + полный Python suite; обновить hashes/evidence.
4. Создать release branch/tag v0.1.0-alpha.3 через проверенный CI, immutable HTTPS assets/checksums. Не перезаписывать существующий релиз.
5. Приватный полный backup production alpha, verify + isolated restore check, затем service-specific deployment/rollback без потери миров, аккаунтов и private identity. До этого live не трогать.
6. Проверить реальный сервис/доступность, обновить PLAY_ALPHA/HANDOFF/ALPHA_PROGRESS и этот файл, снять собственный coordination LOCK в #12.

## Границы и безопасность
Пользователь получил ориентир ещё 30–60 минут при успешных проверках после 18:18 МСК, НЕ гарантированный срок. Ввод пароля в native dialog виден, stock client не проверяет заранее известный server pin: отдельный пароль WarLand, не показывать ввод. RSA/AES-CFB8 не TLS/AEAD. Публичный offline-account-compatible вход без whitelist; владельца egorkrid666 назначать только через приватное подтверждение, не по нику. Старый owner proof истёк, новый не публиковать.

Market/inventory crash-safety, покупки/захваты, авиация/final HUD, полная moderation/recovery и beta10/20/30 остаются незавершёнными; их flags не включать ради релиза. GUI на VPS — loopback/software rendering, не внешняя beta и не нагрузочный тест. Адрес live `201.51.10.116:25565`. Миры, backups, ключи и старые dirty trees не удалять. После окончания диалога работа автоматически не продолжается.
