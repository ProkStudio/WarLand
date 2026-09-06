# WarLand 0.1.0-alpha.2 — limited playable alpha

Клиентский комплект: **WarLand-0.1.0-alpha.2-client.zip**. Установка и вход: [ALPHA_JOIN](https://github.com/ProkStudio/WarLand/blob/release/0.1.0-alpha.2/docs/ALPHA_JOIN.md). Сервер `201.51.10.116:25565`; актуальная доступность и результаты GUI-проверки — [журнал запуска](https://github.com/ProkStudio/WarLand/blob/main/ALPHA_PROGRESS.md).

Fabric 1.21.11 / Loader 0.19.5 / Java 21. В комплекте WarLand companion, Fabric API и публичный pin сервера. Minecraft/Java/launcher, пароли и приватные ключи не распространяются.

## Проверено перед подготовкой
- Exact game source: 2a89fa966f0f3fabf90d785fc22aaebb52655db1, build override mod_version=0.1.0-alpha.2.
- VPS: clean test build successful; JUnit 442 detected /438 passed /4 pre-existing skips /0 failures/errors; Python 156/156.
- Native encrypted-offline register→PLAY, balance, wrong password denied before PLAY, full restart→login, stable server key and exactly one starter grant.
- Six duplicate attempts against a playing incumbent rejected without eviction; delayed LOGIN acknowledgment also rejected while incumbent remains functional.
- Separate disposable synthetic test world. Two clean shutdowns, no server ERROR/Exception in that matrix. No Mojang/session-provider overrides.
- Paper stopped only after user-authorized cutover; complete private backup compared against stopped source. Old directory retained for rollback. Separate new alpha world, non-root service, private UNIX console, RCON/query disabled.

The pipeline repeats Java/Python tests on the release commit, creates the client ZIP and SHA256SUMS. CI artifact digest may differ from the VPS compiler build; compare downloaded files with this release's checksums. Both derive from the same game source. Operator deployment digest is recorded separately in ALPHA_PROGRESS.

## Not a stable/full release
This is a limited alpha. It does not complete inventory crash-safety/market, building purchases, war capture, aviation/final HUD, economy balance or real 10/20/30-player beta. These features remain disabled/incomplete. Native encrypted offline auth is not TLS/AEAD. See journal for the exact extent of graphical client acceptance and external connectivity.
