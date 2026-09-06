# Free safe RTP (integration handoff)

## Scope and integration

This branch adds only `ru.warland.rtp.RtpService`, its pure policy helper, tests and this document. It does not enable the service by itself. Parent integration must call this once during mod initialization (while registries and Fabric callback registration are still open), not from `SERVER_STARTED`:

```java
ru.warland.rtp.RtpService.register(this); // inside CoreRuntime.initialize()
```

Equivalently, call `RtpService.register(coreRuntime)` alongside the other service registrations. There are no changes to CoreRuntime, CoreCommands, global configuration, version, auth, HUD, spawn, profiles or existing worlds in this delegation. No release, merge, deployment or production restart is part of this branch.

## Player contract

- `/rtp` is player-only and free; it never debits or credits currency. Feedback is sent only to the requesting player. It does not create any global `/warp` target.
- Destination is exactly `minecraft:overworld`, never the lobby/custom dimension, Nether or End. Travel from the lobby is allowed; travel from the End is conservatively disabled like core transit.
- Candidates are random around the current overworld spawn, in the inclusive horizontal annulus 1,000–3,000 blocks. Sampling is area-based, then restricted to chunk-interior columns and filtered for safety; accepted destinations are not uniformly distributed over every block.
- Reuses `config.teleportWarmupSeconds` (currently default 8 seconds). That configuration is not changed. The player must stand still on dry ground; position displacement over 5 cm, dimension changes, death, logout, expiration/replacement of the exact auth session, vehicle/passengers, gliding, sleep, fire, water/lava, inventory operations, combat and the nation's active war window reject/cancel the request.
- An already pending core teleport prevents RTP; starting another core teleport while RTP is pending cancels RTP on the next server tick.
- A 180-second cooldown is durably reserved immediately before the final destination check and move. Its remaining time is rounded up in feedback. Login/reconnect does not reset it.

## Safety checks

- The candidate chunk must be a currently available FULL `WorldChunk`. No unloaded heightmap is mistaken for empty terrain.
- Feet are at `WORLD_SURFACE + 1`; no downward cave/roof search and no terrain repair.
- A 3×3 landing pad requires full-cube solid, dry, stable floors and three literal-air blocks of empty collision above them. Leaves and falling floors are rejected. Partial blocks/slabs are not treated as a full floor.
- The surrounding 5×5 footprint, from floor through two blocks above feet, must contain no fluids or hazards. Explicit rejection includes water/lava/waterlogged floors, fire/soul fire, magma, cactus, powder snow, campfires (even unlit), berries, wither roses, dripstone, cobwebs, portals, ice, slime/honey, TNT, tripwire, pressure plates and sculk shriekers. Unknown non-vanilla block IDs fail closed.
- Every footprint column must be outside `CoreRuntime.protectedAt` zones. All claims, including the player's own nation's cities/claims, are excluded with an additional one-chunk moat. There is also a direct serialized SQL claim check before the cooldown is reserved, to catch a claim whose NationsService snapshot has not yet published.
- The whole footprint and actual standing player bounding box must fit inside the current world border with a one-block inset. Numeric full containment is used, not vanilla's intersection-style `WorldBorder.contains(Box)`.
- Standing collision and actual entity/block occupancy are checked with `world.isSpaceEmpty`; oversized player dimensions must fit inside the volume actually checked.
- Authorization, player conditions, current spawn-relative radius, loaded chunk, border, protections, floor, fluids, neighboring hazards and collision are rechecked after the durable reservation, immediately before the main-thread teleport.
- These checks protect the landing at evaluation time. They cannot promise immunity to later player actions, mob attacks, future claim changes or terrain changes after arrival. Existing claim publication uses the NationsService snapshot model; this service does not change that system's concurrency contract.

## Workload and cancellation

- One active RTP request globally, no queued players, at most one unfinished Store operation globally (including after cancellation).
- One RTP-owned chunk ticket at a time, radius zero / FULL; at most one new center request per second. No simulation, forced or persistent tickets.
- At most 32 coordinate proposals, 8 chunk requests, 4 columns per loaded chunk and 32 surface probes per request. At most one surface probe per server tick; at most 100 block cells per probe.
- Search budget: 30 seconds. Individual chunk load budget: 4 seconds. Each Store phase: 5 seconds. The exact-session lease has an outer deadline of warmup + 40 seconds.
- A load timeout ends the entire request and applies a 30-second global admission backoff rather than stacking more generation jobs. Other failures/cancellations use short 2–5 second admission backoffs, separate from the persistent player cooldown.
- The owned ticket is removed on completion, cancellation, disconnect and shutdown. Its 200-tick expiry permits expiration even before load completion as a second cleanup fence. Only the RTP ticket type is removed. If removal throws, the service pauses until restart rather than admit a second possibly overlapping ticket.
- Generation/loading uses normal vanilla asynchronous ticket processing and non-blocking `getWorldChunk` polling. `getChunkFutureSyncOnMainThread` is deliberately not used: inspected 1.21.11 bytecode pumps main-thread tasks until its future completes. No `getChunk`, future `join`, blocking wait or worker-thread world reads are used by RTP.
- Vanilla may generate dependency chunks and finish already scheduled generation after the RTP ticket is removed. The bounds are on this service's admitted requests, not a promise that vanilla only touches one chunk internally. The service never calls block placement/breaking APIs, and never rewrites existing regions or profiles.

## Persistence and late cancellation

Namespace `rtp.cooldown.v1` in the existing `state(namespace,key,json)` table, key = player UUID, value = JSON integer expiry in epoch milliseconds. No SQL schema migration, separate file or in-memory-only cooldown is added. Unrelated state and profile/economic rows are preserved.

The transaction uses the auth lease captured at request admission; it cannot borrow authorization from a replacement player with the same UUID. It atomically rechecks the stored cooldown and claims and writes a new expiry only when allowed. Malformed state, storage errors and arithmetic overflow fail closed.

The cooldown is saved **before** teleport for reconnect/crash safety. A cancellation, timeout, shutdown or move rejection after that transaction has been admitted may leave a cooldown without a teleport. This conservative receipt is not refunded: otherwise disconnect/reconnect could create a retry window. Cancellation feedback during the save phase explains that `/rtp` will show any remaining cooldown. Ordinary search failures before reservation do not create a 180-second cooldown.

## Validation and remaining acceptance

Automated coverage lives in:

- `RtpPolicyTest`: pure terrain, surface/cave, air/headroom, shape/floor, liquid, hazard neighbors, protections, chunk margins, full-border containment, movement, clock/budget boundaries and sampling tests.
- `RtpStateTest`: temporary real SQLite; cooldown persistence/reopen, duplicate reservation, old/replaced/revoked session leases, admitted late cancellation, claim/neighbor claim fences, corruption/overflow, no money/profile writes and unrelated state preservation.
- `RtpWiringTest`: source-level regression tripwires for the actual service bindings, final checks, cancellation, ticket cleanup and forbidden APIs. These are not substitutes for runtime acceptance.

Mapped API signatures and relevant bytecode were inspected from the MC 1.21.11 / Yarn build.6 Gradle cache. Validation uses only the assigned isolated checkout under the shared exclusive build lock as `warland-build`, Gradle 9.2.1 offline/no-daemon/max-workers=1, with a 384 MiB Gradle heap and one 256 MiB test worker.

### QA status — 2026-09-06

- 33 tests were added: 18 pure policy, 10 temporary-SQLite persistence/lease tests, and 5 source-wiring regression tests.
- Local static scope checks passed: five Java files in the RTP package; one teleport call after the final validity guard; no direct block edit, synchronous chunk load, public warp, debit, schema mutation or future-join calls.
- An isolated background `gradle build` was launched for source/test commit `b813dd8ca2cbcc435618b96e6628eaef638be35d`. The Minecraft connection subsequently returned HTTP 429 for every read-only status poll. No exit code, Java compilation result, test count/result or built JAR has been verified. The final cleanup-failure pause hardening additionally needs to be included in the rerun.
- Handoff evidence prefix on the build host: `/opt/warland-build/lobby-rtp-20260906-1743.qa-v1` (`.pid`, `.log`, `.exit`). Assigned checkout: `/opt/warland-build/lobby-rtp-20260906-1743`. Inspect that existing job before starting any new build; do not duplicate a possibly running job. No authenticated git push is configured on that host; branch publication used the authorized GitHub connection.
- This is a source implementation awaiting Java/build and runtime acceptance, not a verified or deployed feature.

Live authenticated RTP, actual cold chunk timing, successful transfer, in-game safety fixtures, two-player contention and logout/damage/claim changes during an actual pending transfer remain parent integration acceptance tasks. No isolated build/test result should be described as a deployed feature.
