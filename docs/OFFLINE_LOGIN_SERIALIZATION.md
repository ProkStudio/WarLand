# Late LOGIN duplicate protection — #29 continuation

Status: SOURCE PREPARATION. The successor branch has not yet passed an exact mapped build, current CI or the new delayed-wire matrix. Not a stable/public-alpha release or permission to enable an offline endpoint.

## Scope and provenance

- Active author: `agent-001-20260906-1445`, user-appointed successor of the previous001.
- Preserve predecessor PR30 and commit `6a5d94b664588ec99c155dcd4532db2ad17fd2cd`; do not rewrite that branch or delete its evidence.
- Combine its opt-in native encrypted-offline implementation with integration `0521221d3dbca5bf8dae4a21dab530cded68759c`, which contains #33/PR35 exclusive CONFIGURATION admission, before acceptance.
- Narrow new production change: a cancellable HEAD injection into `ServerLoginNetworkHandler.tickVerify(GameProfile)` in `OfflineLoginMixin`. Existing early guard, key persistence, explicit public-key pin, owner bootstrap and online-mode behavior are retained.

## Why two distinct late boundaries matter

The actual mapped Minecraft1.21.11 handler has a private `tickVerify(GameProfile)V`. Its body calls `PlayerManager.disconnectDuplicateLogins(UUID)` before `sendSuccessPacket(GameProfile)`. The mapped class read-only SHA256 is `8c72ce7167c3d37ca059b84ec14ab8c1f17374f0ec839e9dab331659fbe319ba`.

The earlier `onKey` check is not a lifetime reservation: verification runs later. The new hook rejects only the incoming offline connection when encryption is missing or the profile UUID already has a playing player, before vanilla can evict the incumbent. The check deliberately does not use `warland$offlineHandshake`: `onKey` already clears that flag. Normal online authentication returns immediately from this hook.

A connection can also delay LOGIN acknowledgement until a different connection has reached CONFIGURATION/authenticated state. The independent `AuthRuntime.configure` exclusive reservation from PR35 is still necessary. The tick guard is not a substitute for it. Never change runtime admission back to trusted-generation `engine.open`.

## Verification levels

- Four `OfflineLoginSerializationTest` regressions check source wiring and preservation of the independent guards. These are not genuine client timing tests and cannot prove that an injection was applied at runtime.
- Read-only mapped method descriptor/call-boundary inspection is complete; compilation and the full suite on the final combined branch remain pending.
- Existing PR35 evidence (432 JUnit detected,428passed,4existing skips;156 Python passed) applies to its exact tested source, not automatically to this new combined source.
- Required new evidence: final commit/tree and artifact digest, actual complete Java/Python/CI results, genuine Fabric startup with production manifest and observed mixin application, bounded delayed packet cases, clean shutdown/log checks, and private synthetic data invariants across restart.

## Planned isolated native-protocol matrix

Use only a newly created own loopback runtime, synthetic accounts/world/database/key and pre-shared synthetic server pin. No production files, real passwords, owner bootstrap or OP changes. Obtain a fresh coordination LOCK and OS flock after peer actual UNLOCK; never infer release from an expected deadline. Keep every fixture/log/result and stop only the process created by this runner.

1. Pin mismatch and encryption/nonce failures reject without WarLand account creation.
2. Valid encrypted first login reaches the CONFIGURATION auth challenge; wrong password does not grant PLAY.
3. A second connection waiting for LOGIN acknowledgement cannot replace a pending incumbent reservation; the incumbent can still authenticate.
4. Repeat a delayed acknowledgement after the incumbent has authenticated/reached PLAY; reject only the newcomer and preserve the authenticated identity/session/data.
5. Exercise late `tickVerify` with an incumbent already playing, then repeated duplicate attempts. Verify the incumbent remains connected; do not call an early `onKey` rejection proof of the late boundary.
6. Disconnect the incumbent explicitly; a later valid reconnect succeeds, including after fresh server restart with preserved synthetic identity and account store.

A deterministic QA-only scheduling hook may be needed to hold the interval between the early check and tickVerify. If the harness cannot demonstrate this interval, report that case as NOT TESTED, not PASS. A headless protocol probe does not prove companion GUI usability, two-human gameplay, public connectivity, moderation, live economy, load safety or a stable release.

## Rollback and remaining gates

This branch is source-only. No server is installed/restarted by publishing it. Before deployment, retain the last verified artifact, config/key/database backup and independently verified restore plan under the release checklist #15. Never roll back CONFIGURATION reservation to permit replacement. For an offline-specific failure, stop the rollout or restore a reviewed online-only configuration/artifact; do not bypass encryption, pinning, authentication or existing protections. Never delete worlds/player data/backups/evidence.

#29/PR30 and #17 encrypted acceptance remain open until genuine combined runtime evidence and independent review are complete. #32 inventory work belongs to002. Shared `AGENT_STATUS.md` remains untouched while its docs handoff is outstanding; live coordination is in #12/#29.
