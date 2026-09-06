# Native login wait-screen hotfix

**0.1.0-alpha.3.1 deployed successfully on 2026-09-06 at17:04:30UTC.** Current operator checkpoint: [AUTH_WAIT_STATUS.md](https://github.com/ProkStudio/WarLand/blob/main/AUTH_WAIT_STATUS.md).

Exact tested game source `79580f8c1f97cc446d32e44031660e5710da6a7a`; deployed JAR SHA256 `65a9627f959e5767290eda576766d98a99807097f072eb5730f1e62402b85e77`. This later commit changes this document only. Existing alpha.3 release tag/assets were not overwritten; a separate3.1 release asset has not yet been published. This is not stable/#15 completion.

## What changed
- AfterAction.NONE keeps the actual login dialog mounted, instead of stranding ignored/stale requests on WaitingForResponseScreen. Vanilla's ordinary clear-dialog handler does not itself dismiss that separate waiting screen.
- Public input-shape feedback for password length, confirmation mismatch and owner-code format; no disclosure of account existence or password correctness.
- Explicit two-minute deadline message and protocol disconnect at expiry.
- No relaxation of nonce/schema/type/size/encryption/session/KDF/rate-limit/owner-proof/pre-PLAY guards. No name-only owner/OP, password reset, real account registration by the agent, data migration or feature-flag changes.

## Verified evidence
- Clean exact-source build: Java479 detected/475passed/4pre-existing registry-dependent skips/0failures; Python210 passed. Files were byte-compared with GitHub.
- Initial new tests that tried registry-dependent dialog constructors under plain JUnit failed (2); that FAILED evidence is retained. Final ordinary tests honestly cover input policy and source wiring. Actual dialog construction/encoding is checked on a booted Fabric server, not described as plain-JVM rendering acceptance.
- Fresh synthetic reserved-owner matrix: missing-account login denial → short-password feedback → wrong proof denial → correct registration → required pack/PLAY/balance; clean restart → wrong-password denial → successful login. Compression256; actual after_action=none observed on every initial and denied dialog, one starter grant, stable identity, SQLite/FK clean, two exit0 shutdowns, no server errors or secret canaries.
- Service-bound deployment, zero online players, private full-runtime archive and tar read-back comparison. Original identity and baseline profile/account/balance/ledger/owner rows preserved. New startup had no errors; production native probe observed NONE and cancelled without registering an account. Code rollback was not needed.

## Limits and recovery
The exact original user input/GUI failure was not reproduced by synthetic wire tests; the affected user must reconnect and confirm actual client entry after the change. No new visual acceptance on the user's device or full120-second timeout runtime matrix is claimed.

Archive `/var/backups/warland-auth-dialog-20260906-170355`; procedure/evidence `/opt/warland-ops/auth-dialog-hotfix-20260906/`. Old JAR retained under runtime/retired-auth-dialog-20260906-170355. A rollback restores code only, NEVER an older database over newer player actions. All keys/accounts/worlds/backups stay private.

Checkout `/opt/warland-build/auth-dialog-fix-20260906-1643`; native evidence `/opt/warland-build/vanilla-dialog-qa-owner-hotfix-v2/result.json`. Owner-retry/compression helpers remain preserved as untracked files in checkout/tools and require a separate cleanup/publication task; they are not part of the tested game-source commit. Own build/deployment locks are released in #12. A fresh owner proof is shared privately only while the actual owner remains unbound; no proof/password belongs here.
