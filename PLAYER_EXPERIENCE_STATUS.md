# WarLand — согласованные изменения игрового входа и оформления

2026-09-06 20:22 МСК: ответы владельца получены; продолжение подтверждено в20:42. Ветка `agent/lobby-20260906-1722/fortress-chat-auth`, база `b4f7d222f7b97393babde6762f977c0df303faff` (doc-only после протестированного79580f8).

## Точное ТЗ
- Обычный Minecraft Java1.21.11. Игрок сначала видит изолированное лобби, вводит /register или /login через чат. Существующие аккаунты/пароли сохраняются; никаких прав/инвентаря/экономики/профиля до успешной аутентификации. Пароли не должны попадать в серверные журналы, чат, suggestions или audit. Пользователь предупреждён о локальной истории обычного клиента.
- После каждого успешного входа — лобби, не место выхода.
- Существующую основу спавна сохранить и достроить до160×160. Город-крепость, графит/золото. Все зоны: ратуша/инфоцентр, казармы/полигон, торговая площадь ПОКА ДЕКОР, обучение/правила, порталы/площадкаRTP. Игроковые постройки/миры не стирать.
- /rtp бесплатно, на безопасную сушу обычного мира на расстоянии1000–3000блоков от его спавна, интервал180сек. Не в бою/чужом городе/воде/лаве; bounded chunk loading, отмена при disconnect/смене lease. Без изменения блоков мира развития.
- TAB с реальной ролью владельца [OWNER], никакого OP по нику. Графит/золото, читаемый header/footer.
- Правая панель: ник, город, деньги, роль, государство, время в игре. Деньги обязательны (пользователь повторно подчеркнул).
- Админка через чат: объяснить ВСЕ фактически доступные владельцу команды с примерами. Не строить /admin GUI вместо запрошенных команд; не выдавать незавершённые функции за рабочие.

## Текущее состояние
- Production пока0.1.0-alpha.3.1, рабочая версия и backup: AUTH_WAIT_STATUS.md. Сервер ещё не изменён этой итерацией.
- Read-only проверка подтвердила, что реальный owner уже bound. Нельзя выдавать новый bootstrap, менять владельца или требовать повторной регистрации.
- Начата проверка auth/lifecycle/content/core. Реализация новой итерации и её тесты ещё НЕ завершены.
- Собственная новая ветка создана; старый dirty checkout `/opt/warland-build/auth-dialog-fix-20260906-1643` сохранён без reset/clean. Создавать отдельный checkout для новой итерации.

## Gates до установки
Auth lobby must not hydrate/save real player inventory/stats/world data or expose powers before proof; duplicate connection must not evict owner. Reuse existing KDF/rate limits/nonce/transport and identity, deny all non-auth effects. Test wrong/successful password, retries, timeout, duplicate/reconnect/restart, secret canaries in logs, profile/starter exactly-once, no pre-auth data writes, current vanilla graphical flow.

Build/test under `/opt/warland-build/build.lock`; production service-specific zero-online preflight, private full runtime backup under backup lock, ready+version+data checks and code-only rollback without overwriting newer player state. UI/world require actual visual inspection, not just compilation. Existing release tags/assets remain immutable. Full stable#15 and unrelated gameplay work are not automatically complete.

Сохранять результаты/ошибки/коммиты сюда по мере выполнения. Координация #12. Не публиковать пароли, bootstrap, identity.bin, БД, playerdata или приватные архивы. Не обещать фоновую работу после окончания диалога.
