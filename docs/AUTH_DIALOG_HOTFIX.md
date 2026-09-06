# Native login wait-screen hotfix

2026-09-06; user-reported vanilla GUI wait after rejected login/registration. Candidate version `0.1.0-alpha.3.1`, branch `agent/release-20260906-1633/auth-dialog-wait`. This is a narrow hotfix candidate, not the completed stable release and not a replacement of the immutable alpha.3 release assets.

## Evidence before the change
The production server, SQLite worker and KDF worker were responsive. Two actual configuration sessions reached the 120-second timeout without completing account registration. No password or owner code was logged or published.

An exact-alpha.3 isolated synthetic reserved-owner matrix passed: missing-account login denial, short-password denial, wrong synthetic proof denial, valid-proof registration, required pack/PLAY/balance; clean restart, wrong-password denial and login. Compression256, stable identity, one synthetic starter grant, SQLite/FK clean. This does not reproduce the reporting user's exact client/input or prove their GUI works.

Source inspection of Minecraft1.21.11 confirms the dialog transitions to a separate WaitingForResponseScreen with WAIT_FOR_RESPONSE; the ordinary clear-dialog handler does not itself dismiss that separate screen. WarLand also deliberately drops stale/malformed/noncurrent actions. Such ignored actions must not leave the user stranded on a waiting overlay.

## Change
- Keep the actual dialog mounted with AfterAction.NONE, so a dropped request does not create an indefinite waiting screen and ClearDialog works against the current dialog.
- Explain public input-shape errors (password length, confirmation mismatch, owner-code format) without exposing account existence or password validity.
- Explain the two-minute configuration deadline and send a proper protocol disconnect when it expires, instead of only closing the channel.
- Keep nonce/schema/type/size/encryption/current-session guards, KDF/rate limits, owner proof, reserved name and pre-PLAY barrier unchanged. No name-only owner/OP, new real account, credential reset, DB migration or feature flag change.

## Verification / continuation
Eleven added regressions cover real dialog mode, input feedback and source-level timeout wiring; full exact-source Java/Python build is pending at this checkpoint. Do not deploy unless build, fresh isolated exact-JAR owner/retry/restart and safe backup checks pass. Actual GUI acceptance must be separately recorded; wire tests do not prove rendering. Working checkout `/opt/warland-build/auth-dialog-fix-20260906-1643`, build logs `/opt/warland-build/auth-ui-fix-{java,python}-v1.*`. Prior QA evidence `/opt/warland-build/vanilla-dialog-qa-owner-retry-v1/result.json`.

No live credentials, account records, worlds, runtime configurations or private backup contents belong in GitHub. The previous owner code may expire during debugging: issue a new private 15-minute proof only if the real owner is still unbound; do not reuse a synthetic QA proof. A code rollback must preserve all player data created since the backup.
