# Атомарный допуск подключения (#33)

Агент `agent-001-20260906-1445`; координация #12, связано с review PR30/#29 и checklist #15. Это source-level исправление, не готовый публичный вход или stable release.

## Дефект и решение

На integration `758cbcd17b6a5055fbd6b73e078ef8bb564245ac` Admission.open заменял nonce действующей сессии; Authentication.open затем отменял её незавершённые запросы. Позднее второе CONFIGURATION-подключение могло отозвать pending/authenticated сессию без пароля. Ранней проверки PlayerManager недостаточно.

Новый Admission.reserve выполняет очистку истёкших записей, проверку занятого UUID и создание nonce под одним monitor. Authentication.reserve отказывает до отмены incumbent requests. Единственная runtime точка AuthRuntime.configure вызывает reserve; обработка отказа отключает только новое соединение. Pending/authenticated nonce, исходные deadlines и KDF/DB work сохраняются. Повторный stale disconnect остаётся nonce-specific. Capacity128 и TTL120 секунд/8 часов не изменены.

Legacy open намеренно сохранён для доверенного явного переключения поколения и существующих regression fixtures. Сетевые адаптеры НЕ должны его вызывать. В production grep найден только engine.reserve в AuthRuntime и явный выбор reserve/open внутри Authentication. Это не новая разрешающая настройка и не ослабление transport/owner guards.

## Фактическая проверка

2026-09-06, отдельный nonroot VPS checkout, JDK21, pinned Gradle9.2.1 (ZIP checksum проверен), один worker/common flock. Рабочие службы не перезапускались.

- Red: неизменённые production файлы758cbcd; три новых probes вызывают существующий open (reserve тогда отсутствовал). Все3 скомпилированы и упали на отсутствии duplicate rejection: pending, authenticated, queued DB login. Не compilation failure и не выдуманная атака на сервер.
- Green: тот же base + ровно3 production patch и2 новых test files; `python3 tools/build.py --offline clean test build` exit0. JUnit432 detected,428 passed,4 прежних PlanSafety skips,0 failures/errors,42 XML. Все14 новых тестов прошли:8 Admission +6 engine/SQLite/wiring. Старые тесты не изменены.
- `PYTHONPATH=tools python3 -m unittest discover -s tools/tests -v`:156/156 PASS, exit0,13.439 секунд.
- `git diff --check` PASS. Перед публикацией fetched integration всё ещё758cbcd.
- Executable JAR SHA256 `4b611f622c1283ef113abdcc83aa9215c2f3930205e86bd01ecf15f6a9386478`. Это base+patch, НЕ бинарник неизменённого base.
- Production SHA256: Admission `2320effdde7011b8d0cabd57ad11f49c9ce791673117e5fc23ccd86ec495b682`; Authentication `7eecdffb55f79b7ef5a960fb54b06364ddf983315bc07a630c89b925aa22086b`; AuthRuntime `e8b4a3db7ae026f7b1786b93616f4ea29ad9ba8f88ccce2361522475b2f24846`.
- Build log SHA256 `25badf16b8972ea42d670570d8b2a0be7c11560697b8a5dbf1aa9ede2bf31af1`; Python log `fb9ab5b066e3ae020a9dc690db27c357d32b492e28870af1422af08e0fd4cc01`. Red XML/logs сохранены отдельно в собственном agent-001-20260906-1445-evidence.

## Ограничения и следующий gate

Это engine/SQLite/concurrency и source-wiring tests, НЕ реальные delayed LOGIN/CONFIGURATION packets или companion GUI. Для PR30 нужны отдельно поздний tickVerify, combined-source exact build и реальная изолированная protocol matrix; reserve сам по себе не закрывает все vanilla duplicate-login пути. Pending-first denial до120 секунд и общий network abuse остаются отдельными рисками; чужой пароль/owner не обходит reserve.

Четыре старых registry/worldgen skips, Java/Gradle deprecation, SQLite version semver warning и Loom remapping warning не скрыты и не объявлены исправленными. Repository CI/независимое review нового опубликованного SHA проверяются отдельно в PR. Ни этот JAR, ни transport PR30 не установлен на рабочие Paper/staging; owner/OP не выдавался. Инвентарь/торговля #32, moderation #19 и полный #15 остаются отдельными задачами.

## Откат

Перезапуск для source-only PR не требуется; schemas/configs/flags/world/player data не меняются. До runtime acceptance не устанавливать JAR. Откат кода — обычный revert без force push/удаления веток, с пониманием возвращения duplicate-session denial-of-service. Будущий deployment требует актуального backup/restore и отдельного LOCK/плана сохранения новых данных. Общий AGENT_STATUS обновляется только после согласованного handoff #17; первичные результаты находятся в #12/#33/PR.
