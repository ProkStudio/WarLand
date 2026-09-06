# Инвентарь рынка: снимки и планы передачи (#32)

Статус: source-only prerequisite, не включённая торговля и не доказательство атомарности Minecraft player data. Автор agent-002-20260906-1428; координация #12, alpha #25, full release #15.

## API

- `new MarketStackCodec(server.getRegistryManager())`: registry-aware `ItemStack.VALIDATED_CODEC` с JsonOps. Вызывать на серверном потоке. Exact1.21.11 signatures проверены read-only по mapped classfile, не угаданы по прежней версии.
- `encode/decode`: immutable StackData с canonical count-one payload, фактическим count/maxCount. Roundtrip сравнивает полный item/component набор; потерянные неперсистентные компоненты запрещены. Не заменять сериализацию именем предмета.
- `store/restore`: версия1, Minecraft1.21.11, количество/лимит/полный unit JSON. Строка предназначена для `market_items.stack`, максимум65536 UTF-16 units как в текущем MarketRepository. Неверная версия, неизвестные/дублированные/потерянные поля, неканоническая форма и некорректные компоненты отвергаются повторным encode comparison. Исключения codec не публикуют item contents.
- `capture`: caller передаёт все слоты ванильного инвентаря, выбранный hotbar slot и курсор из одного server-thread наблюдения. Не включает произвольные внешние контейнеры: будущий adapter должен закрыть их и требовать player screen.
- `InventorySnapshot.deposit(slot,count)`/`delivery(stack)`: только расчёт immutable before/after/transfer, без Minecraft/БД writes. Main storage0..35; armor/offhand/дополнительные slots сохраняются, но не расходуются. Доставка сначала объединяет одинаковые components/maxCount, затем использует пустые main slots; недостаточное место — отказ целиком, никогда drop.
- Непустой курсор запрещает планирование. Полный snapshot ограничен262144 символами и содержит выбранный слот/курсор/оборудование. Payloads length-framed, разделители внутри компонентов не создают коллизии. StackData сам по себе не валидирует registry: не создавать его из сетевых данных в обход codec.

## Фактические проверки и границы

Локально чистый planner скомпилирован Java25 compiler `--release21`,6251 standalone assertions PASS, включая2080 комбинаций deposit/delivery conservation. Это не запуск Java21/JUnit/Minecraft. Добавлены14 JUnit planner tests и6 JSON-envelope tests; mapped build/JUnit через repository CI проверяются в PR. Нет новых Disabled/Assumptions и нет подмены реального ItemStack fixture строками с заявлением о Minecraft acceptance.

Реальные `ItemStack` roundtrips с registry, именованным/зачарованным предметом, container components и damaged stack должны быть выполнены под Fabric Knot в отдельном runtime. Они НЕ выполнены этим локальным standalone запуском. До этого задача не закрывается как runtime-ready. Нужен контроль max-count поведения реальных предметов, пустых слотов и roundtrip после restart.

```bash
python3 tools/build.py clean test build
PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v
```

## Следующий реальный путь deposit → trade → delivery

1. Полный InventoryGuard: original session lease, player screen/empty cursor, packet/interactions/drop/pickup/death/respawn/teleport/другие feature boundaries; pending intent блокирует вход до reconciliation.
2. На серверном потоке capture+plan; guarded prepareDeposit/prepareDelivery commit, потом повторная проверка сессии и before перед единственным применением after.
3. Подтвердить реально долговечное сохранение player data и read-back именно дискового snapshot; клиентское подтверждение не доказательство. Ошибка/неоднозначность оставляет RECOVERY, не освобождает предметы/деньги.
4. MarketFeature GUI и guarded list/buy/cancel используют server-side price/operation identity и bounded mailbox. DB buy атомарен только внутри Store и не заменяет inventory boundary.
5. Фактические two-client/crash/restart/replay/full-inventory tests. Только затем разрешать buyer/AH flags.

## Откат

Только новые не подключённые classes/tests/doc. Перезапуск/backup рабочих данных не нужны, runtime схемы/flags/server не менялись. Откат обычным revert; preserve branches/evidence/player data. #15 не закрывается. Не устанавливать этот prerequisite как якобы работающий рынок.
