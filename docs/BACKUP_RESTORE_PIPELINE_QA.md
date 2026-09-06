# Синтетическая проверка полного цикла backup → restore

Задача [#26](https://github.com/ProkStudio/WarLand/issues/26), агент `agent-002-stability-20260906-135209`; база `a58102ba4aaa18c6a120bdad0847b8021540a1a6`. Это regression coverage формата и целостности, **не резервная копия рабочих данных, не запуск восстановленного Minecraft и не закрытие release checklist #15**.

## Что проверяется

Новый `tools/tests/test_backup_restore_pipeline.py` соединяет два неизменённых production-инструмента: настоящий Bash `backup.sh` создаёт tar.gz и SHA256, затем `restore_snapshot.restore` читает именно этот архив и восстанавливает в новый приватный каталог. Раньше lifecycle-тесты backup и вручную созданные tar-fixtures restore проверялись отдельно.

Fixture содержит только выдуманный SQLite `example(id, amount)` с двумя строками, синтетический world marker, properties и неисполняемый launcher marker. Реальные аккаунты, миры, конфиги, пароли и игровые БД не используются. SQLite-соединение закрывается до backup, конкурентных writers нет.

Шесть сценариев:

1. Первоначально активный сервис: архив → restore → те же SQLite bytes/rows, `quick_check=ok`, неизменный source, приватные mode700/600, правильный JSON report и исключение logs.
2. Первоначально неактивный сервис: такой же round-trip без единого start.
3. Изменение архива после записи checksum: restore отвергает его до создания destination.
4. Повреждённая SQLite: tar/checksum успешно создаются, но restore отклоняет БД, очищая только свой новый temporary destination; исходник и архив сохраняются.
5. Уже существующий destination: данные не перезаписываются.
6. Символическая ссылка внутри source: tar создаётся, restore строго отклоняет link, не читая и не меняя внешний synthetic target.

Пункты 4 и 6 — намеренная демонстрация границ: сообщение backup `Verified archive` означает проверку tar/checksum и состояния сервиса, **не** доказательство восстанавливаемости данных. Для допуска обновления всё равно нужен отдельный успешный restore/boot.

## Безопасность harness

Используется композиция с fixture из `test_backup`, а не наследование TestCase: старые26 тестов не обнаруживаются повторно. Заглушки systemctl/id/date, ограниченный PATH и перенаправление единственного абсолютного lock pathname остаются прежними. tar/gzip/SHA256/flock/SQLite настоящие; host systemctl, сеть, Java и Minecraft не вызываются. Очистке подвергаются только новые synthetic temporary directories. Restore дополнительно проверяется на отсутствие новых вызовов lifecycle helpers.

## Выполненные проверки

```bash
PYTHONPATH=tools python3 -m unittest discover -s tools/tests -p test_backup_restore_pipeline.py -v
```

Локальный Linux/Python3.13: **6/6 PASS**, без пропусков, 2026-09-06T11:13:27Z. Исходный restore_snapshot.py побайтно совпал с Git blob `019ed70accf15126e3391f195169080ddafafee3`; backup/test harness — точные файлы ранее проверенного PR24. Это добавление покрытия: production-defect/red-fix здесь не заявляется.

SHA256 нового test-файла: `adff9f60fff86b00bf05dd865910dc6cb13d8942f9b2ccad9e3606976126fc7d`; local log: `426bf0dc5fa1f7e38ce797a1cc02c115d1c11114a88a95867c0e91ea20a24178`.

Полная штатная команда: `PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v`. Результаты полного exact-HEAD suite, CI и независимого review фиксируются отдельно в #26/PR после исполнения; арифметическая сумма тестов не считается прогоном.

## Ограничения и откат

Не проверяются реальные systemd start/stop и таймауты, активный WAL/конкурентная запись, авария ОС/диск, настоящая WarLand schema/аккаунты, запуск мира, Mojang/client UI, нагрузка, перенос UUID и disaster recovery рабочих данных. Ни одного сервиса или рабочего файла эта задача не меняет; перезапуск для тест/docs PR не нужен. Откат — обычный revert двух новых файлов, без изменения данных, веток других агентов или истории Git.

Общий AGENT_STATUS.md не перезаписывается поверх незавершённого handoff #17: проверенные сведения передаются текущему/следующему владельцу через Coordination #12. Для реального релиза по-прежнему необходимы актуальные backup, проверенное восстановление копии и согласованный rollback.
