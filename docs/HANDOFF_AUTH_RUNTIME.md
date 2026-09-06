# Auth runtime — implementation and live-check handoff

Updated 2026-09-06 after 07:28 UTC. PR10: https://github.com/ProkStudio/WarLand/pull/10 → development/initial-release. Still draft, unmerged and not deployed. Root development HANDOFF.md tracks cross-feature release progress.

## Exact code and completed verification

Tested code: `1c034feac191e328bc712e57af98ce8aed956b29`. PR9 is already merged; its final engine `c4b547e8836468f147c0ddc16035f78edbb85ba6` was brought into this branch by `1dfc5ad95978d01523541de3c2425dd3abe660c1`. Do not overwrite it with the older 946bfcd implementation.

The Request.getId compile failure was fixed in ea91 and is NOT pending. Both final checks passed:
- https://github.com/ProkStudio/WarLand/actions/runs/34018268394/job/101445930803
- https://github.com/ProkStudio/WarLand/actions/runs/34018267001/job/101445926678

Fresh full VPS build/test of exact1c034fe: **337 Java detected /333 passed /4 preexisting skipped /0 failures or errors; 34 Python passed. All41 auth tests passed without skips.** AuthBoundary2, AuthCancellation2, AuthHardening3, AuthMixinWiring2, AuthPipeline2, AuthTest24, AuthWire3, RuntimePolicy3. Build is complete, not running.

Checkout `/opt/warland-build/auth-runtime-1c034fe`; reports `auth-runtime-1c034fe-tests.json` and `auth-runtime-1c034fe-build.{log,exit,pid}` under `/opt/warland-build`. Executable `build/libs/warland-0.1.0-alpha.1.jar`, SHA256 `d98d18790c4804c9c04b91d656e356e44db7f7c3c99b8742079d069a0ea32bbe`.

## Implemented security boundaries

- AuthRepository starts after Store, Authentication closes before Store; stopping suppresses late startup. Whole pipeline capped at9 before Store/KDF, cancellation-safe secret cleanup and permit retention until internal work actually settles.
- Mandatory BEFORE_CONFIGURE task `warland:authentication`; no PLAY/world/profile/starter grant before authentication, gameplay waits for profileReady. Source inspection of pinned Fabric early tasks is backed by live negative checks, not yet a positive client acceptance test.
- Actual connection encryption AND online-mode required, including local offline-development. AuthClient masks password/confirmation/bootstrap, blocks selection export and secret narration, checks current handler/encryption. Passwords are not commands/chat history.
- Wire v1 request: version byte, nonce UUID, operation0 login/1 register, unsigned-short UTF-16 code-unit counts and chars for password<=128, confirmation<=128, bootstrap<=43; total<=640 bytes, no trailing bytes. Password12–128; registration confirmation before KDF. Identity/peer come from authenticated GameProfile/socket, not payload. Challenge: nonce + status0 prompt/1 generic denial/2 accepted.
- Per-connection in-flight and bounded account/peer/global throttles. Nonce, connection object, current handler/session, expiry and stopping checked before effect publication. Profile transaction and callback repeat current-auth checks.
- Packet gate and required handler Mixins cover credential commands/completion, common custom-click/dialog, simulation/pickup/damage/death and selected async/core callbacks. This is not proof of complete legacy/third-party callback coverage.
- Parent hardening1c034fe adds required AuthConfigurationMixin at priority3000, cancellable HEAD guards on BOTH onReady and endConfiguration; exact pinned bytecode/registration tests pass. RuntimePolicy and AuthSourceAccess also require non-silent console plus OWNERS permissions, real server output identity and no entity. Restricted/silent function contexts must not become trusted console.
- Bootstrap command creates15-minute256-bit proof only on operator execution; CREATE_NEW/NOFOLLOW file0600 in checked POSIX0700 directory. Does not print token or overwrite file. Maximal WarLand staff permissions require durable owner binding AND current authenticated/profileReady session; vanilla OP is not granted or a fallback.
- Password reset/change deliberately unexposed until password revision CAS, pending/active session revocation and login publication are serialized and tested.

## Live evidence: what actually passed

VPS access recovered. Dedicated synthetic copy ONLY, `127.0.0.1:25569`, user warland-build, exact executable above. Source fixture `/opt/warland-build/release-qa/final-runtime`, never production/staging. Minecraft protocol774 was extracted from version.json, not guessed.

V2 ran two complete boot/console/probe/private-file/stop cycles, including restart. Both ready=true, exit0, no ERROR/Exception lines. Both probes passed Minecraft LOGIN into CONFIGURATION, then received a WarLand-specific disconnect and no premature Finish/PLAY:
- ProbeInsecure advertises `warland:auth_challenge` and `fabric:registry/sync`; offline/unencrypted access returns “WarLand: безопасная авторизация недоступна. Переподключитесь с клиентом WarLand.”
- ProbeEarly immediately forges configuration READY; returns “WarLand: завершите защищённую авторизацию перед входом.”

Both cycles exercised actual console/Accessor/private-file wiring:43-byte base64url fixture,0600 file/0700 directory, repeated command preserved file digest, plaintext deleted. No auth account or owner was registered. Profile/auth account counts remained0; ops empty. SQLite quick_check=ok and0 FK errors. Services minecraft.service/Paper and warland-staging.service/Fabric stayed active and unchanged. Test processes stopped; port25569 free afterward.

Paths under `/opt/warland-build`:
- `auth-runtime-smoke-1c034fe-v2.py`, SHA256 `771280c74caa434fa986608c05e5de365bb48ed2d83a7943df9c5190d0968aa3`.
- `auth-runtime-smoke-1c034fe-v2.{log,pid,exit}`; exit0.
- `auth-runtime-smoke-1c034fe-v2/auth-boot-{1,2}.log`.
- `auth-runtime-1c034fe-smoke-v2-result.json`, SHA256 `9e4c4a5df832371948e3ec236c29b0d0ec3661b39a303516e7772ae853d845e9`.

V1 is preserved but its ProbeNoMod stopped at Fabric's missing-client dependency gate; its generic success is not evidence of the WarLand transport check. V2 advertises the pinned RegistrySyncPayload.ID, requires WarLand in the disconnect packet, buffers partial frames under an absolute deadline and refuses existing runtime/report/exit files before mutation. Do not rerun V1 over old evidence: its finally can overwrite reports. Use fresh paths for new cases.

Remaining WARNs are recorded, not silently called clean: optional disabled MarketFeature, intentional insecure-mode warning in negative fixture, cold-start lag around7 seconds and duplicate handleDisconnection warnings. No loaded-player performance acceptance or client rendering test was performed.

## Exact next work / remaining release gates

1. Positive dedicated-client online+encrypted registration, wrong-password refusal, successful login after restart; one profile/starter grant only. Prove auth success completes configuration exactly once, then profileReady with initial teleport/player-loaded/keepalive/chunk acknowledgments. Do not weaken transport guards to get a green test. A strictly isolated synthetic identity-provider could test the encrypted integration path without real credentials, but must be labelled synthetic rather than Mojang/user verification.
2. Actual companion-client UI/Mixins: resize/waiting, error/disconnect transitions, oversize Unicode/surrogate paste, no secret narration/history/clipboard. Byte/char-array cleanup does not guarantee wiping Java strings, Netty or third-party buffers. Never use production passwords as canaries.
3. Full packet matrix before auth and at/after TTL: signed/unsigned command aliases/namespaces/execute/completion/chat, movement/vehicles/teleports, block/entity/inventory/creative/recipe/drop/pickup/XP/projectiles, damage/death/respawn, unknown/split/custom payloads and reconfiguration. Forged READY negative check is now done, not this entire matrix.
4. Audit all legacy callbacks and queued DB effects for nonce-bound authorization at publication. UUID-only credit/debit intentionally supports system/offline work and is not itself an authenticated user-action boundary. Review moderation publication, tick loops, respawn replacement and third-party hooks.
5. Crash/reconnect/KDF saturation/duplicate UUID/stale nonce/registration/profile transaction/DB errors/shutdown/eight-hour expiry. Preserve committed durable registration but never infer current session authorization from a stored account row. Verify idempotent starter grants.
6. Remaining operator/source/POSIX/replay/expiry tests, and usable private operator channel for a systemd server without interactive stdin. Real function/tick/scheduled/RCON/command-block identities require live checks beyond unit truth tables. Do not enable unrestricted RCON.
7. Only after acceptance, privately transfer real proof and bind egorkrid666. **NO production bootstrap, real owner binding, role or OP grant has occurred.** Synthetic fixture keys were deleted and are not user enrollment.

No full release or live deployment approval is inferred from unit tests or negative smoke. Original design snapshot remains in Git history at `1c034fe/docs/HANDOFF_AUTH_RUNTIME.md`; its old HTTP429/merge9/revalidation statuses are superseded by this document. Local computer files have shown persistence failures; serial creation+validation+packaging in one terminal call worked. Durable GitHub/VPS paths and hashes take precedence over assumed local files.
