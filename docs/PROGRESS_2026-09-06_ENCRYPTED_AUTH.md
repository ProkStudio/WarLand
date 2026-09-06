# WarLand: encrypted authentication acceptance — 2026-09-06

## Current checkpoint

Active work: PR #10 (`feature/auth-runtime`). Root `HANDOFF.md` on `development/initial-release` remains cross-feature history; `main` remains original specification. This file is a continuation checkpoint, not a claim of release or background work after the conversation ends.

## New reproducible blocker and fix

The new headless client completed actual RSA/AES-CFB8 encrypted Minecraft LOGIN against a strictly loopback-only synthetic identity provider and reached CONFIGURATION using the unmodified `1c034fe` executable. It then **failed** with `Registry sync before authentication`. Inspection of pinned Fabric registry/networking sources confirmed that Fabric registers its registry-sync task in the default BEFORE_CONFIGURE phase before WarLand. The WarLand pre-auth packet gate blocks its completion reply, so placing the authentication task later can deadlock positive login. Earlier negative-only probes could not detect this.

Fix committed at **43be0575d57d7c81984d98da84e306818535fbc2**: `ConfigurationOrder.beforeDefaults` registers the auth task in a named phase ordered before Fabric's default phase. The packet allowlist is NOT loosened; early registry-sync acknowledgments remain blocked. Three Java regressions exercise actual Fabric event ordering in both registration orders and guard runtime wiring.

- Harness `tools/auth_encrypted_smoke.py` first committed in `e48153485a3f1cc6f354fe3dc10b81397871c419`.
- Harness SHA256: `331f02c1f4ec8c4c3c944cdab6ea588928b36734c9d73a1288b94df12298be32`.
- Initial failing evidence preserved under `/opt/warland-build/auth-encrypted-qa-e481534-v1`; report `encrypted-auth-result.json`, driver `.driver.log` alongside the directory. Test server stopped normally, exit0; harness success=false.
- New fixed Java build is started under unprivileged `warland-build`, checkout `/opt/warland-build/auth-runtime-43be057`; log/result/PID `/opt/warland-build/auth-runtime-43be057-build.{log,exit,pid}`. **At this checkpoint completion is not yet confirmed.** Inspect the exit file and process before rerunning anything.
- Thirteen new deterministic Python framing/isolation tests passed locally (fragmented frames/timeouts, coalescing, absolute deadline, bounds/EOF, UTF-16 and loopback restrictions). Full suite and corrected encrypted acceptance remain pending.

## Acceptance target

Registration, wrong-password rejection, successful login after a dedicated-server restart, exactly one profile/account/starter grant; configuration completion only after auth, PLAY profile-ready welcome, authorized balance response, teleport/player-loaded/chunk/keepalive control packets. The harness refuses root execution, nonempty source accounts and previous output paths; uses disposable in-memory passwords; asserts no plaintext canaries in server logs. It acknowledges Fabric registry sync but is NOT a real client renderer/remapper.

Synthetic authlib overrides exist only in the isolated test JVM arguments, never in production configuration. This is NOT Mojang account verification, real-player/client-GUI acceptance, owner enrollment or production release. No real secrets/player databases/worlds have been published. No services, firewall, live mod files, financial flags or owner roles have been changed.

## Next actions

1. Inspect fixed build results; repair failures rather than declaring a pass.
2. Run the exact new executable with a fresh output name `auth-encrypted-qa-*` and its verified SHA256. Do not rerun against the previous evidence directory.
3. Resolve any remaining positive-path failure; rerun negative transport/forged-READY cases on the new code; record hashes, exact counters and warnings.
4. Preserve final status here and in integration HANDOFF. PR10 is still draft/unmerged/unreleased.
5. Remaining release scope is still large: companion UI, packet/TTL/crash matrix, inventory escrow/market, cities/content/vehicles, moderation, real beta and Paper→Fabric migration. Do not label an alpha a completed release.
