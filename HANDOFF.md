# WarLand — продолжение разработки

Обновлено 6 сентября 2026. Это конспект фактического состояния, не обещание фоновой работы после завершения чата.

## Где находится работа

- `main`: исходное ТЗ и этот указатель, **не готовый релиз**.
- `development/initial-release`: объединённая интеграционная alpha. [Полный межмодульный конспект](https://github.com/ProkStudio/WarLand/blob/development/initial-release/HANDOFF.md).
- `feature/auth-runtime`: текущая авторизация и [PR #10](https://github.com/ProkStudio/WarLand/pull/10), пока draft/unmerged/unreleased.
- [Текущие подробности и точки продолжения авторизации](https://github.com/ProkStudio/WarLand/blob/feature/auth-runtime/docs/PROGRESS_2026-09-06_ENCRYPTED_AUTH.md).

## Новое в этом продолжении

Выявлен реальный deadlock защищённого входа: Fabric ставил синхронизацию реестров раньше auth-задачи, а pre-auth защита блокировала её подтверждение. Исправлено явным порядком фаз BEFORE_CONFIGURE, без ослабления packet gate. Runtime-исправление **43be0575d57d7c81984d98da84e306818535fbc2**.

Полная сборка точного runtime на VPS: **340 Java тестов обнаружены, 336 успешны, 4 прежних пропуска, 0 ошибок; 34 прежних Python успешны**. Добавлены ещё 20 Python-регрессий тестовых клиентов: 13 framing/isolation + 7 mutation/denial tests; обе группы отдельно успешно выполнены. Полная Python suite до последних 7 тестов также прошла: 47/47. Финальные общие итоги уточняются в подробном конспекте.

**Положительная сквозная проверка пройдена** на отдельной синтетической копии Fabric: реальный online-mode + RSA/AES-CFB8 transport, регистрация → PLAY, отказ неверному паролю без PLAY, вход после перезапуска → PLAY. Получены profile-ready welcome, ответ /balance, teleport/player-loaded/chunk/keepalive control packets. В БД ровно один профиль/auth account, один стартовый грант1500, баланс1500 после перезапуска, integrity/FK проверки успешны.

**Дополнительная encrypted-матрица пройдена:** forged READY, неверный nonce и несовпадающее подтверждение пароля отклонены именно WarLand без изменения профилей/auth/economy. После них тот же свежий runtime снова прошёл регистрацию и вход после перезапуска.

Используется синтетический loopback identity-provider, а не реальный вход Mojang. Headless-клиент не проверяет GUI/модели/рендеринг. Это НЕ полная клиентская/нагрузочная/релизная приёмка. Остались WARN отсутствующего отключённого MarketFeature, cold-start/server-lag примерно8–12сек и duplicate-disconnection на отрицательных пробах.

## Сохраняемые доказательства

Все пути ниже на VPS под `/opt/warland-build`, не включают опубликованные миры/БД/секреты:
- `auth-runtime-43be057`: исходники и `build/libs/warland-0.1.0-alpha.1.jar`.
- SHA256 JAR: `57462746ed169c10fb0178d3bc642aa97c8b2ae85665a00d56653e8f42bb3d84`.
- `auth-runtime-43be057-build.{log,exit,pid}`; сборка завершена, java=0/python=0.
- `auth-encrypted-qa-43be057-v1/encrypted-auth-result.json`; success=true, оба server exit0. SHA256 `be5b13c6e72030d23692d5bf5001c7d26208e3f8d4dde19dea6fb38e277297e0`.
- `auth-encrypted-qa-43be057-matrix-v1/admission-matrix.json`; success=true. SHA256 `1fa4a91173690da2588575381d892deb861d67114d4addafb4a40dd82b2e1609`.
- `auth-encrypted-qa-43be057-matrix-v1/encrypted-auth-result.json`; success=true. SHA256 `217fa961a39a8d76cb017e0fe2f7350695c049f65e05907f4990ec89de9c3cca`.
- Предыдущее воспроизведение ошибки сохранено отдельно: `auth-encrypted-qa-e481534-v1`; его success=false ожидаемо, это не успешная приёмка.

Старые документы до этой проверки, утверждающие, что positive encrypted login ещё не проверен, описывают более ранний срез. При возобновлении сначала читать текущий подробный конспект по ссылке выше. Не перезаписывать предыдущие QA-папки; для каждого прогона новое имя.

## Что ещё не сделано

Релиз не завершён. Нужны настоящий companion-client/UI и Mojang-проход, остальная packet/TTL/respawn/crash/async-callback матрица, приватная эксплуатационная процедура владельца, inventory escrow/реальный рынок, остальные города/политика/контент/техника, модерация/восстановление, настоящая бета10/20/30 и проверенная Paper→Fabric/UUID миграция. Смена/сброс пароля намеренно не включены до согласованной revocation/CAS-проверки.

Рабочие Paper и прежний private Fabric staging не заменялись, их миры/конфиги не трогались. Production bootstrap, привязка реального владельца и OP не выдавались. Секреты и данные игроков в GitHub не публиковать; dirty worktrees прежних задач сохранять.
