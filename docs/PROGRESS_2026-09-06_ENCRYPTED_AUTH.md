# WarLand: encrypted authentication acceptance — 2026-09-06

## Current checkpoint

Work resumed from PR #10 (`feature/auth-runtime`, head `6275cbcbc7fbfe2914af1e0a46fe9c7839c9c5c8`). Runtime code last tested at `1c034feac191e328bc712e57af98ce8aed956b29`; newer commits are documentation. Integration branch remains `development/initial-release`; `main` is the original specification. This file is a continuation checkpoint, not a claim of release or background execution.

Confirmed on the VPS: Paper and the original private Fabric staging are running; neither has been modified in this session. Old development checkouts contain unrelated dirty files and must be preserved. Prior negative auth probes and full test results are described in `HANDOFF.md` on the integration branch and `docs/HANDOFF_AUTH_RUNTIME.md` here.

## Current target

Build a reproducible, strictly loopback-only synthetic identity-provider/client harness for the **online-mode + genuinely encrypted** dedicated-server authentication path. Exercise registration, wrong-password rejection, successful login after restart, configuration completion, and absence of duplicate profile/starter grants. A synthetic provider is NOT Mojang account verification, real-player acceptance, or proof of the companion GUI.

No production passwords/tokens/player databases/worlds are to be copied into GitHub. Use disposable test identities and a fresh runtime copied only from the existing synthetic QA fixture. Do not weaken authentication, bypass encrypted transport, or enable economic feature flags to make tests pass. Do not replace working Paper/Fabric services with the unaccepted candidate.

## Progress / evidence

- Repository and server access verified; auth candidate and prior evidence located.
- Local sandbox cannot resolve github.com; source inspection and Java execution use the connected VPS, while new scripts can be prepared/validated in the sandbox and committed through GitHub.
- Positive encrypted harness: **not implemented or run yet**. No new passing acceptance results claimed.

## Next

1. Inspect pinned authlib endpoints and Minecraft 1.21.11 packet IDs before implementing a synthetic loopback identity provider and encrypted wire client.
2. Run in a fresh directory/port under the unprivileged build user with absolute deadlines, bounded frames, and cleanup; keep original evidence intact.
3. Fix actual failures, add regressions, run build/tests, and update this file with exact revision/hashes/outcomes.
4. Remaining release scope is still large: companion UI, packet/TTL/crash matrix, inventory escrow and market, cities/politics/content/vehicles, moderation, real beta and migration. Do not label an alpha a completed release.
