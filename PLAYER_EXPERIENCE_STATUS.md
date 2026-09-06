# WarLand — согласованные изменения игрового входа и оформления

Ответы владельца получены 2026-09-06 в20:22 МСК; продолжение подтверждено в20:42 и21:54. **Статус после22:26: RTP установлен вalpha.3.2; owner chat help реализован и опубликован вalpha.3.3, но установка3.3 заблокирована RTP-приёмкой.** Подробности: [RELEASE_CONTINUATION.md](RELEASE_CONTINUATION.md), [OWNER_HELP_STATUS.md](OWNER_HELP_STATUS.md).

## Точное ТЗ
- Обычный Minecraft Java1.21.11. Игрок сначала видит изолированное лобби, вводит /register или /login через чат. Существующие аккаунты/пароли сохраняются; никаких прав/инвентаря/экономики/профиля до успешной аутентификации. Пароли не должны попадать в серверные журналы, чат, suggestions или audit. Пользователь предупреждён о локальной истории обычного клиента.
- После каждого успешного входа — лобби, не место выхода.
- Существующую основу спавна сохранить и достроить до160×160. Город-крепость, графит/золото. Все зоны: ратуша/инфоцентр, казармы/полигон, торговая площадь ПОКА ДЕКОР, обучение/правила, порталы/площадкаRTP. Игроковые постройки/миры не стирать.
- /rtp бесплатно, на безопасную сушу обычного мира на расстоянии1000–3000блоков от его спавна, интервал180сек. Не в бою/чужом городе/воде/лаве; bounded chunk loading, отмена при disconnect/смене lease. Без изменения блоков мира развития.
- TAB с реальной ролью владельца [OWNER], никакого OP по нику. Графит/золото, читаемый header/footer.
- Правая панель: ник, город, деньги, роль, государство, время в игре. Деньги обязательны (пользователь повторно подчеркнул).
- Админка через чат: объяснить ВСЕ фактически доступные владельцу команды с примерами. Не строить /admin GUI вместо запрошенных команд; не выдавать незавершённые функции за рабочие.

## Текущее состояние
- Production0.1.0-alpha.3.2,ready18:32:39UTC; source2c13ebad,installed JARac515284. Аккаунты/миры/ключ/owner сохранены. Подробная история backup/rollback — в основномконспекте.
- Для3.2 ранее прошла положительная exact-artifact RTP-проверка, повтор и cooldown после restart. При холодной генерации всё ещё возможны безопасные отказы; это открытая проблема воспроизводимости/производительности, не завершённая load-приёмка.
- **Owner help выполнен в исходниках и alpha.3.3:** `/wladmin`, `/wladmin help [страница]`,27 записей с примерами/ограничениями и действующими правами. Точный опубликованный JAR прошёл native non-owner/seeded-owner/auth/help/restart/no-data-change suite.
- **Owner help ещё не на рабочем сервере:** RTP regression3.3 получил3 bounded cold-load отказа; положительный перенос/restart для этого JAR не подтверждён. Версия3.2 оставлена без изменений; prepared deployment не запускался. Код RTP(10classes) идентичен3.2.
- **Auth-chat, изолированное лобби до proof, lobby-on-every-join, крепость/TAB/HUD ещё НЕ реализованы этой итерацией.** Вход пока через прежнюю native-форму. Графическая приёмка UI/мира остаётся отдельным gate.
- Реальный owner уже bound. Не выдавать новый bootstrap, не менять владельца, не требовать повторной регистрации.
- PR41/42/43/44 merged в `agent/lobby-20260906-1722/fortress-chat-auth`, текущий head76cd439. Main — docs-first; не путать его с релизным source. Старые dirty worktrees сохранены без reset/clean. Собственный help/QA checkout `/opt/warland-build/owner-help-20260906-1858`; точные ветки/логи в OWNER_HELP_STATUS.

## Gates до установки следующих изменений
Auth lobby must not hydrate/save real player inventory/stats/world data or expose powers before proof; duplicate connection must not evict owner. Reuse existing KDF/rate limits/nonce/transport and identity, deny all non-auth effects. Test wrong/successful password, retries, timeout, duplicate/reconnect/restart, secret canaries in logs, profile/starter exactly-once, no pre-auth data writes, current vanilla graphical flow.

Build/test under `/opt/warland-build/build.lock`; production service-specific zero-online preflight, private full runtime backup under backup lock, ready+version+data checks and code-only rollback without overwriting newer player state. UI/world require actual visual inspection, not just compilation. Existing release tags/assets must not be overwritten. Full stable#15 and unrelated gameplay work are not automatically complete.

Сохранять результаты/ошибки/коммиты по мере выполнения. Координация#12. Не публиковать пароли,bootstrap,identity.bin,БД,playerdata или приватные архивы. Не обещать фоновую работу после окончания диалога.
