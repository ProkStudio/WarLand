# Контент WarLand — интеграционный журнал

Ветка `feature/content` основана на `development/initial-release`. Реализация и испытания продолжаются; этот первый commit НЕ является готовой поставкой.

## Решения для интегратора

- Entry point: `ru.warland.content.ContentFeature implements Feature`, no-arg constructor. Вызывать `initialize` при запуске мода, затем `serverStarted`, `tick`, `stopped` через ядро. Общие API, build и fabric.mod.json модуль не меняет.
- Столица: **новое отдельное измерение `warland:capital`**, flat/void; оригинальная процедурная архитектура 193×193 на Y=80. Никаких записей в Верхний мир при генерации, очистки территории или импорта миров. Только vanilla blocks, 0 NPC/entities.
- `setSafeSpawn` вызывается ТОЛЬКО после полной генерации/проверки. Радиус 128. Варпы: `capital`, `capital_market`, `capital_quests`, `capital_transport`, `capital_training`.
- **Критичный hook ядра:** callbacks `UseBlock` столицы должны отрабатывать ДО общего `canBuild`-запрета ядра. Сейчас Protection.register перед ContentFeature.initialize блокирует терминалы. Зарегистрируйте content callbacks раньше Protection или дайте узкое разрешение на read-only terminal dispatch (не разрешать vanilla use).
- До регистрации safe spawn ядро всё равно должно защищать всё `warland:capital` от explosions/fire/fluids/pistons. Content блокирует ручные break/use и урон, но полноценные server mixins принадлежат ядру.
- `canBuild` проверяется во всём объёме и при каждом размещении городского здания. Сейчас API разрешает wilderness; самостоятельных nation/city roles API не предоставляет. Это честный prototype building privilege, а не законченная городская экономика.
- Покупки зданий **выключены по умолчанию**. Есть рабочие preview, resource bill, unique payment ID, staged material reservation, бюджетные блоки. Неполные платежи и material/world ambiguity после рестарта уходят в ручную сверку, не в автоматический refund/replay. Инвентарь, мир и SQLite НЕ объявляются атомарными.
- Daily/weekly activity rewards — только репутация в одном state blob с claim markers, без предметов и валютного faucet.
- Custom enchantments, полноценные PvE/PvP events, city tiers/upkeep, season data, packet/client QA пока НЕ заявляются готовыми.

Публикуются только content sources/resources/tests и этот документ. Production Paper не останавливался и не изменялся. Итоговые SHA, файлы, тесты, ограничения и процедура отката будут добавлены после реальной компиляции.
