# WarLand — согласованные изменения игрового входа и оформления

2026-09-06 20:22 МСК: ответы владельца получены; продолжение подтверждено в20:42. Исходная база b4f7d222f7b97393babde6762f977c0df303faff. **Обновление после21:32МСК: RTP опубликован и установлен вalpha.3.2; остальные пункты ниже остаются в работе.** Актуальный release/deployment checkpoint: [RELEASE_CONTINUATION.md](RELEASE_CONTINUATION.md).

## Точное ТЗ
- Обычный Minecraft Java1.21.11. Игрок сначала видит изолированное лобби, вводит /register или /login через чат. Существующие аккаунты/пароли сохраняются; никаких прав/инвентаря/экономики/профиля до успешной аутентификации. Пароли не должны попадать в серверные журналы, чат, suggestions или audit. Пользователь предупреждён о локальной истории обычного клиента.
- После каждого успешного входа — лобби, не место выхода.
- Существующую основу спавна сохранить и достроить до160×160. Город-крепость, графит/золото. Все зоны: ратуша/инфоцентр, казармы/полигон, торговая площадь ПОКА ДЕКОР, обучение/правила, порталы/площадкаRTP. Игроковые постройки/миры не стирать.
- /rtp бесплатно, на безопасную сушу обычного мира на расстоянии1000–3000блоков от его спавна, интервал180сек. Не в бою/чужом городе/воде/лаве; bounded chunk loading, отмена при disconnect/смене lease. Без изменения блоков мира развития.
- TAB с реальной ролью владельца [OWNER], никакого OP по нику. Графит/золото, читаемый header/footer.
- Правая панель: ник, город, деньги, роль, государство, время в игре. Деньги обязательны (пользователь повторно подчеркнул).
- Админка через чат: объяснить ВСЕ фактически доступные владельцу команды с примерами. Не строить /admin GUI вместо запрошенных команд; не выдавать незавершённые функции за рабочие.

## Текущее состояние
- Production0.1.0-alpha.3.2 ready18:32:39UTC. PR41 merged вagent/lobby-20260906-1722/fortress-chat-auth; release tag2c13ebad, installed JARac515284. Подробные hashes/backup/rollback/tests в RELEASE_CONTINUATION.md.
- RTP runtime проверен на exact downloaded release artifact: успех, repeat denial, cooldown denial послеrestart/login, баланс/аккаунт сохранены. Production code swap проверен поhash/ready/version/identity/data/cancel-only auth probe; graphical пользовательская приёмка не подменяется synthetic wire.
- При холодной генерации RTP может безопасно отмениться по4сек лимиту; повторить через30сек. Первый такой FAILED QA сохранён. Остальные cooldown180сек после успешного RTP переживаютreconnect/restart.
- **Auth-chat, изолированное лобби до proof, lobby-on-every-join, крепость/TAB/HUD/owner help ещё НЕ реализованы и не установлены этой итерацией.** Вход пока через прежнюю native форму.
- Реальный owner уже bound; приdeployment identity/baseline auth_owner сохранены. Не выдавать новыйbootstrap, не менятьвладельца и не требовать повторной регистрации.
- Исходные/старые dirty checkout сохранены безreset/clean; новый RTP worktree /opt/warland-build/release-20260906-1806-rtp. Для следующей задачи создавать отдельную копию от свежей интеграции и проверятьCLAIM/LOCK.

## Gates до установки следующих изменений
Auth lobby must not hydrate/save real player inventory/stats/world data or expose powers before proof; duplicate connection must not evict owner. Reuse existing KDF/rate limits/nonce/transport and identity, deny all non-auth effects. Test wrong/successful password, retries, timeout, duplicate/reconnect/restart, secret canaries in logs, profile/starter exactly-once, no pre-auth data writes, current vanilla graphical flow.

Build/test under `/opt/warland-build/build.lock`; production service-specific zero-online preflight, private full runtime backup under backup lock, ready+version+data checks and code-only rollback without overwriting newer player state. UI/world require actual visual inspection, not just compilation. Existing release tags/assets must not be overwritten. Full stable#15 and unrelated gameplay work are not automatically complete.

Сохранять результаты/ошибки/коммиты по мере выполнения. Координация#12. Не публиковать пароли,bootstrap,identity.bin,БД,playerdata или приватныеархивы. Не обещать фоновую работу после окончаниядиалога.
