# WarLand: encrypted auth — continuation checkpoint

Updated 2026-09-06 after the 08:00 UTC checks. PR10 remains draft/unmerged/unreleased. Main-branch HANDOFF.md is now a discoverable entry point. Production and the original staging have not been changed.

## Completed: real positive-path blocker fixed

Runtime **43be0575d57d7c81984d98da84e306818535fbc2** orders the authentication task before Fabric's default BEFORE_CONFIGURE phase. Before this fix, the e481534 harness reproduced an actual deadlock: Fabric registry sync arrived before auth, and its acknowledgment was correctly blocked by the pre-auth packet gate. The fix changes event ordering, NOT the packet allowlist or encrypted-transport requirement.

Three Java regression tests use the real Fabric event implementation to prove order in both mod-registration orders and guard runtime wiring.

## Verified evidence (not full release)

Exact43be VPS build: 340 Java detected /336 passed /4 preexisting skips /0 failures/errors; 34 old Python passed. Then current test-only head d502016 was checked: **54/54 Python passed**; src tree remained identical to43be (`c68e4ba90dbe216b60a0ffd579abba44c7d831f2`). Both d502016 CI checks passed:
- https://github.com/ProkStudio/WarLand/actions/runs/34020525904/job/101452147763
- https://github.com/ProkStudio/WarLand/actions/runs/34020523946/job/101452140130

Executable43be SHA256: `57462746ed169c10fb0178d3bc642aa97c8b2ae85665a00d56653e8f42bb3d84`.

### Positive synthetic encrypted acceptance: PASS

Strictly isolated Fabric copy, online-mode=true, genuine RSA/AES-CFB8 login; authlib hosts overridden only in the test JVM to a one-use, loopback-only synthetic identity provider. No Minecraft/password-auth guard was bypassed.

- Registration: statuses0→2, exactly one configuration finish, PLAY, profile-ready welcome, authorized /balance response, teleport/player-loaded/chunk/keepalive control packets.
- After full server restart: wrong password0→1, no configuration finish/PLAY, no persistent count/balance changes; then correct login0→2 and the same positive controls.
- Exactly one profile, auth account, wallet, ledger operation and starter grant1500; wallet1500 after restart. SQLite quick_check=ok, FK errors0. Both processes exit0, no ERROR/Exception lines. Synthetic passwords absent from server log. No OP.
- Repeated successfully in the adversarial suite below.

Paths under `/opt/warland-build`:
- `auth-runtime-43be057`; build result `auth-runtime-43be057-build.{log,exit,pid}`, completed java=0/python=0.
- `auth-encrypted-qa-43be057-v1/encrypted-auth-result.json`; SHA256 `be5b13c6e72030d23692d5bf5001c7d26208e3f8d4dde19dea6fb38e277297e0`.
- Failing old-code reproduction: `auth-encrypted-qa-e481534-v1` (success=false, preserved intentionally).

### Encrypted invalid-admission matrix: PASS

`tools/auth_admission_matrix.py` checks forged READY, stale nonce and mismatched confirmation. Each receives an encrypted WarLand-specific rejection, never an accepted challenge/configuration finish; no profile/auth/economy changes. Same fresh fixture then completes registration and restart/wrong-password/login. Matrix verifier excludes its three rejected *Minecraft identity* acceptances from the base verifier's count of three; six synthetic identity exchanges total, not three total.

- `auth-encrypted-qa-43be057-matrix-v1/admission-matrix.json`; SHA256 `1fa4a91173690da2588575381d892deb861d67114d4addafb4a40dd82b2e1609`.
- Same directory `encrypted-auth-result.json`; SHA256 `217fa961a39a8d76cb017e0fe2f7350695c049f65e05907f4990ec89de9c3cca`.

### Offline/unencrypted + private bootstrap regression: PASS

Adapted earlier verifier, new artifact and entirely fresh paths; two boot/restart cycles. WarLand denied insecure transport and forged READY; profile/auth counts0. Synthetic bootstrap file0600/directory0700, repeat preserved file, plaintext deleted. No OP, DB integrity/FKs pass, both exits0.

- `auth-runtime-smoke-43be057-v3.py`; SHA256 `41e76f6e4188f82c07d5820020585758d3a7b18991c056128952662713328836`.
- `auth-runtime-43be057-smoke-v3-result.json`; SHA256 `07a726b10e1495d8b00c747879036fe8aff4a40b89feb63e55a8b55266f4fd52`.
- `auth-runtime-smoke-43be057-v3.{log,pid,exit}`; completed exit0.

All those QA processes stopped; port25569 free at final check. Original Paper/Fabric services active. WARNs remain: disabled absent MarketFeature, synthetic offline warnings in negative suite, startup/server lag about7–12sec and duplicate handleDisconnection on rejection. This is not load acceptance.

## New security work — inspect before resuming

Independent read-only audit found:
1. P1: Moderation protects bootstrap owner only through an online player, ignoring durable auth_owner for offline targets. Fix being developed separately in `fix/auth-offline-owner`; do not assume merged/validated.
2. P1: queued actor-sensitive writes can execute after session revocation if disconnected before Store work starts. Treasury deposit/withdraw is being fixed separately in `fix/auth-treasury-lease`; moderation and other legacy paths must still be audited explicitly. Never merely suppress post-commit cache publication: committed durable state must remain consistent.
3. P2: TextFieldWidget clips before its predicate, allowing 128 ASCII + an emoji to silently become128 ASCII. Fixed in **600938ab6adbf5725c7097fa1a4c8e02c667233e** with full-insertion UTF-16/selection validation before super.write, rejection of invalid/filter-changing characters, preserved secret masking/selection-export protection, and six regressions. Code is committed; new build `/opt/warland-build/auth-runtime-600938a-build.{log,exit,pid}` was started, result pending at this checkpoint. Checkout `auth-runtime-600938a`. Do not report43be evidence as validation of the newer binary.

No real Mojang account/client GUI/rendering test, owner enrollment, full packet/TTL/respawn/crash matrix or release has been completed. Headless registry acknowledgments do not validate a real client's registry remapping/rendering.

## Resume

1. Inspect the600938 build and the two isolated fix branches/PRs; review/merge only passing, understood changes into feature/auth-runtime. Preserve unrelated dirty server checkouts.
2. Rebuild the exact integrated head and rerun fresh-path acceptance as needed. Never reuse QA outputs or publish synthetic/private DB/world files.
3. Finish remaining session-revocation/legacy paths and actual companion UI/operator-channel checks. No real bootstrap/egorkrid666 binding/OP has occurred.
4. Inventory escrow/real market, cities/politics/content/vehicles, moderation/recovery, real10/20/30 beta and Paper→Fabric/UUID migration remain. These are not waived by auth tests.
