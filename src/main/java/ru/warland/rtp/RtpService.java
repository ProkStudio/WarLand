package ru.warland.rtp;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.EntityPose;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import ru.warland.WarLand;
import ru.warland.core.CoreRuntime;
import ru.warland.data.Store;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static net.minecraft.server.command.CommandManager.literal;

/** Free, private /rtp requests. Register during mod initialization, not SERVER_STARTED. */
public final class RtpService {
    static final String COOLDOWN_NAMESPACE = "rtp.cooldown.v1";
    private static final Set<CoreRuntime> REGISTERED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static ChunkTicketType ticketType;
    private final CoreRuntime runtime;
    private final ChunkTicketType ticket;
    // Server-thread confined: one request, one owned chunk ticket, no pending-player queue.
    private Request active;
    private CompletableFuture<?> storageWork;
    private long lastFinished, admissionBackoff, lastChunkRequest;
    private boolean hasFinished, hasChunkRequest, stopping;

    private enum Stage { READING, WARMUP, SEARCHING, SAVING }
    record Reservation(boolean accepted, long until) {}

    private static final class Request {
        final ServerPlayerEntity player;
        final ServerWorld originWorld, world;
        final double originX, originY, originZ;
        final long opened, warmup, totalBudget;
        final AtomicBoolean valid = new AtomicBoolean(true);
        final BooleanSupplier lease;
        Stage stage = Stage.READING;
        CompletableFuture<Long> reading;
        CompletableFuture<Reservation> saving;
        long ioStarted, searchStarted, loadStarted;
        int proposals, chunkRequests, columns, probes;
        ChunkPos chunk;
        RtpPolicy.Column firstColumn;
        BlockPos destination;

        Request(CoreRuntime runtime, ServerPlayerEntity player, ServerWorld world, long now) {
            this.player = player; this.world = world; originWorld = player.getEntityWorld();
            originX = player.getX(); originY = player.getY(); originZ = player.getZ();
            opened = now; ioStarted = now;
            warmup = runtime.config.teleportWarmupSeconds * 1_000_000_000L;
            totalBudget = warmup + RtpPolicy.SEARCH_NANOS + 2 * RtpPolicy.IO_NANOS;
            BooleanSupplier capturedSession = runtime.auth.lease(player); // Capture exactly once, on main thread.
            lease = () -> valid.get() && capturedSession.getAsBoolean()
                    && !RtpPolicy.expired(opened, System.nanoTime(), totalBudget);
        }
    }

    private RtpService(CoreRuntime runtime, ChunkTicketType ticket) {
        this.runtime = runtime; this.ticket = ticket;
    }

    public static synchronized void register(CoreRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (REGISTERED.contains(runtime)) return;
        if (ticketType == null) {
            // Radius zero below means FULL for just the requested center. No simulation/forced tickets.
            // Non-persistent and allowed to expire even if generation never completes (200 ticks).
            ticketType = Registry.register(Registries.TICKET_TYPE, Identifier.of("warland", "rtp"),
                    new ChunkTicketType(200L, ChunkTicketType.FOR_LOADING | ChunkTicketType.CAN_EXPIRE_BEFORE_LOAD));
        }
        RtpService service = new RtpService(runtime, ticketType);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                literal("rtp").requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                        .executes(context -> service.request(context.getSource().getPlayerOrThrow()))));
        ServerTickEvents.END_SERVER_TICK.register(service::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (service.active != null && service.active.player == handler.player)
                service.finish(service.active, null, 2_000_000_000L);
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, damage, blocked) -> {
            Request r = service.active;
            // Damage dealt as well as received cancels; even a blocked hit is not an RTP escape window.
            if (r != null && (entity == r.player || source.getAttacker() == r.player))
                service.finish(r, cancellation(r, "RTP отменён: бой или полученный урон."), 2_000_000_000L);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            service.stopping = true;
            if (service.active != null) service.finish(service.active, null, 0);
        });
        REGISTERED.add(runtime);
    }

    private int request(ServerPlayerEntity player) {
        // No DB query, ticket, random search or state write is admitted before authentication.
        if (stopping || !runtime.online(player)) {
            runtime.reply(player, "RTP недоступен: авторизуйтесь и дождитесь готовности профиля."); return 0;
        }
        if (!runtime.gate(player)) return 0;
        String reason = blocked(player);
        if (reason != null) { runtime.reply(player, reason); return 0; }
        long now = System.nanoTime();
        if (active != null || storageWork != null && !storageWork.isDone()
                || hasFinished && !RtpPolicy.expired(lastFinished, now, admissionBackoff)) {
            runtime.reply(player, "RTP занят или восстанавливается после поиска. Повторите чуть позже."); return 0;
        }
        ServerWorld world = runtime.server().getOverworld();
        if (world == null || !World.OVERWORLD.equals(world.getRegistryKey())) {
            runtime.reply(player, "Верхний мир недоступен; RTP отменён."); return 0;
        }
        Request r = new Request(runtime, player, world, now);
        if (!r.lease.getAsBoolean()) { runtime.reply(player, "Сессия истекла; RTP отменён."); return 0; }
        active = r;
        r.reading = readCooldown(runtime.store, player.getUuid(), r.lease);
        storageWork = r.reading;
        runtime.reply(player, "Бесплатный RTP: проверяем ожидание. Не двигайтесь и не вступайте в бой.");
        return 1;
    }

    private String blocked(ServerPlayerEntity player) {
        if (!player.isAlive() || player.isRemoved() || player.isSpectator() || player.hasVehicle()
                || player.hasPassengers() || player.isSleeping() || player.isGliding()
                || !player.isOnGround() || player.isTouchingWater() || player.isInLava() || player.isOnFire())
            return "RTP доступен только живому игроку, стоящему на суше, вне техники и опасности.";
        if (runtime.inCombat(player.getUuid()) || runtime.inventoryLocked(player.getUuid()))
            return "RTP недоступен в бою или во время операции с инвентарём.";
        if (runtime.teleports.containsKey(player.getUuid())) return "Сначала завершите или отмените другое перемещение.";
        if (World.END.equals(player.getEntityWorld().getRegistryKey())) return "RTP из Края отключён.";
        var member = runtime.nations.member(player.getUuid());
        if (member != null && runtime.wars.wars().stream().anyMatch(war -> runtime.wars.window(war, System.currentTimeMillis())
                && (war.attacker().equals(member.nation()) || war.defender().equals(member.nation()))))
            return "Во время боевого окна вашего государства RTP отключён.";
        return null;
    }

    private boolean playerValid(Request r) {
        return !stopping && runtime.online(r.player) && r.lease.getAsBoolean()
                && r.player.getEntityWorld() == r.originWorld && blocked(r.player) == null
                && !RtpPolicy.moved(r.originX, r.originY, r.originZ, r.player.getX(), r.player.getY(), r.player.getZ());
    }

    private void tick(MinecraftServer server) {
        Request r = active;
        if (r == null) return;
        long now = System.nanoTime();
        try {
            if (server != runtime.server() || !playerValid(r)) {
                finish(r, cancellation(r, "RTP отменён: движение, бой, смена мира или завершение сессии."), 2_000_000_000L);
                return;
            }
            if (r.stage == Stage.READING) {
                if (!r.reading.isDone()) {
                    if (RtpPolicy.expired(r.ioStarted, now, RtpPolicy.IO_NANOS))
                        finish(r, "Хранилище не ответило вовремя; RTP отменён.", RtpPolicy.IO_NANOS);
                    return;
                }
                long until = r.reading.getNow(0L);
                long remaining = RtpPolicy.remainingSeconds(until, System.currentTimeMillis());
                if (remaining > 0) { finish(r, "RTP будет доступен через " + remaining + " сек.", 0); return; }
                r.stage = Stage.WARMUP;
                runtime.reply(r.player, "Бесплатный RTP: подготовка " + runtime.config.teleportWarmupSeconds
                        + " сек., затем поиск безопасной поверхности. Не двигайтесь.");
            }
            if (r.stage == Stage.WARMUP) {
                if (!RtpPolicy.expired(r.opened, now, r.warmup)) return;
                r.stage = Stage.SEARCHING; r.searchStarted = now;
            }
            if (r.stage == Stage.SEARCHING) {
                if (RtpPolicy.expired(r.searchStarted, now, RtpPolicy.SEARCH_NANOS)) {
                    finish(r, "Безопасная точка не найдена за отведённое время. Попробуйте позже.", RtpPolicy.IO_NANOS);
                    return;
                }
                search(r, now);
            } else if (r.stage == Stage.SAVING) {
                if (!r.saving.isDone()) {
                    if (RtpPolicy.expired(r.ioStarted, now, RtpPolicy.IO_NANOS))
                        finish(r, cancellation(r, "Сохранение ожидания не завершилось вовремя; RTP отменён."), RtpPolicy.IO_NANOS);
                    return;
                }
                Reservation result = r.saving.getNow(null);
                if (result == null) throw new IllegalStateException("Missing RTP reservation");
                if (!result.accepted()) {
                    finish(r, "RTP будет доступен через " + RtpPolicy.remainingSeconds(result.until(), System.currentTimeMillis()) + " сек.", 0);
                    return;
                }
                // Durable cooldown BEFORE the move. Never teleport on a failed/late DB write.
                if (!playerValid(r) || result.until() <= System.currentTimeMillis() || !destinationValid(r)) {
                    finish(r, cancellation(r, "Точка или условия изменились; RTP отменён."), 2_000_000_000L);
                    return;
                }
                BlockPos feet = r.destination;
                boolean moved = r.player.teleport(r.world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                        Set.of(), r.player.getYaw(), r.player.getPitch(), true);
                finish(r, moved ? "Вы прибыли в безопасную точку Верхнего мира. Бесплатно. Следующий RTP через "
                        + RtpPolicy.remainingSeconds(result.until(), System.currentTimeMillis()) + " сек."
                        : cancellation(r, "Перемещение отклонено сервером."), 2_000_000_000L);
            }
        } catch (RuntimeException error) {
            WarLand.LOG.warn("RTP rejected after a storage or safety-check failure", error);
            finish(r, cancellation(r, "RTP не выполнен: проверка безопасности или хранилище недоступны."), RtpPolicy.IO_NANOS);
        }
    }

    private void search(Request r, long now) {
        if (r.chunk == null) {
            if (!RtpPolicy.hasBudget(r.proposals, r.chunkRequests, r.probes)) {
                finish(r, "Безопасная точка не найдена. Лимит поиска исчерпан; попробуйте позже.", RtpPolicy.IO_NANOS);
                return;
            }
            if (hasChunkRequest && !RtpPolicy.expired(lastChunkRequest, now, RtpPolicy.CHUNK_INTERVAL_NANOS)) return;
            r.proposals++;
            BlockPos spawn = r.world.getSpawnPoint().getPos();
            var random = ThreadLocalRandom.current();
            RtpPolicy.Column sampled = RtpPolicy.sample(random, spawn.getX(), spawn.getZ());
            ChunkPos chunk = new ChunkPos(sampled.x() >> 4, sampled.z() >> 4);
            RtpPolicy.Column column = interiorColumn(chunk);
            if (!preflight(r.world, spawn, column.x(), column.z())) return;
            r.chunk = chunk; r.firstColumn = column; r.columns = 0; r.loadStarted = now;
            r.chunkRequests++; lastChunkRequest = now; hasChunkRequest = true;
            // Verified 1.21.11: addTicket schedules normal async loading; radius 0 maps to FULL.
            // Do NOT use getChunk/getChunkFutureSyncOnMainThread: both can pump/block the main thread.
            r.world.getChunkManager().addTicket(ticket, chunk, 0);
            return;
        }
        WorldChunk chunk = r.world.getChunkManager().getWorldChunk(r.chunk.x, r.chunk.z); // Non-blocking getOrNull.
        if (chunk == null) {
            if (RtpPolicy.expired(r.loadStarted, now, RtpPolicy.LOAD_NANOS))
                // End the entire request on a stuck load; don't pile new generation onto it.
                finish(r, "Загрузка местности заняла слишком долго; RTP отменён. Повторите позже.", RtpPolicy.SEARCH_NANOS);
            return;
        }
        if (r.columns >= RtpPolicy.COLUMNS_PER_CHUNK || r.probes >= RtpPolicy.MAX_PROBES) { releaseChunk(r); return; }
        RtpPolicy.Column column = r.columns++ == 0 ? r.firstColumn : interiorColumn(r.chunk);
        r.probes++;
        int x = column.x(), z = column.z();
        int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) + 1;
        r.destination = new BlockPos(x, y, z);
        if (!destinationValid(r)) return;
        // Recheck the exact captured session immediately before scheduling the only state mutation.
        if (!playerValid(r)) { finish(r, "RTP отменён: условия перемещения изменились.", 2_000_000_000L); return; }
        r.stage = Stage.SAVING; r.ioStarted = now;
        r.saving = reserveCooldown(runtime.store, r.player.getUuid(), r.lease, x >> 4, z >> 4, System::currentTimeMillis);
        storageWork = r.saving;
    }

    private static RtpPolicy.Column interiorColumn(ChunkPos chunk) {
        var random = ThreadLocalRandom.current();
        int width = 16 - 2 * RtpPolicy.CHUNK_MARGIN;
        return new RtpPolicy.Column((chunk.x << 4) + RtpPolicy.CHUNK_MARGIN + random.nextInt(width),
                (chunk.z << 4) + RtpPolicy.CHUNK_MARGIN + random.nextInt(width));
    }

    private boolean preflight(ServerWorld world, BlockPos spawn, int x, int z) {
        if (!World.OVERWORLD.equals(world.getRegistryKey()) || !RtpPolicy.inAnnulus(spawn.getX(), spawn.getZ(), x, z)
                || !RtpPolicy.interior(x, z) || claimsNear(world, x >> 4, z >> 4)) return false;
        for (int dx = -RtpPolicy.NEIGHBOR_RADIUS; dx <= RtpPolicy.NEIGHBOR_RADIUS; dx++)
            for (int dz = -RtpPolicy.NEIGHBOR_RADIUS; dz <= RtpPolicy.NEIGHBOR_RADIUS; dz++)
                if (!insideBorder(world, x + dx, z + dz, x + dx + 1, z + dz + 1)
                        || runtime.protectedAt(world, new BlockPos(x + dx, spawn.getY(), z + dz))) return false;
        return true;
    }

    private boolean claimsNear(ServerWorld world, int cx, int cz) {
        String dimension = world.getRegistryKey().getValue().toString();
        // Keep a one-chunk moat around every claimed/city chunk, including the player's own nation.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (runtime.nations.claim(dimension, cx + dx, cz + dz) != null) return true;
        return false;
    }

    private static boolean insideBorder(ServerWorld world, double minX, double minZ, double maxX, double maxZ) {
        var border = world.getWorldBorder();
        // WorldBorder.contains(Box) is intersection-style in vanilla; require full containment + 1 block instead.
        return RtpPolicy.containsBox(border.getBoundWest() + 1, border.getBoundNorth() + 1,
                border.getBoundEast() - 1, border.getBoundSouth() - 1, minX, minZ, maxX, maxZ);
    }

    private boolean destinationValid(Request r) {
        if (r.chunk == null || r.destination == null || !World.OVERWORLD.equals(r.world.getRegistryKey())) return false;
        WorldChunk chunk = r.world.getChunkManager().getWorldChunk(r.chunk.x, r.chunk.z);
        if (chunk == null) return false;
        BlockPos feet = r.destination, spawn = r.world.getSpawnPoint().getPos();
        if (!preflight(r.world, spawn, feet.getX(), feet.getZ())) return false;
        RtpPolicy.Terrain terrain = new RtpPolicy.Terrain() {
            public String dimension() { return r.world.getRegistryKey().getValue().toString(); }
            public boolean loaded() { return (feet.getX() >> 4) == r.chunk.x && (feet.getZ() >> 4) == r.chunk.z; }
            public int bottomY() { return r.world.getBottomY(); }
            public int topY() { return r.world.getTopYInclusive(); }
            public int surfaceY(int x, int z) { return chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) + 1; }
            public boolean insideBorder(int x, int z) { return RtpService.insideBorder(r.world, x, z, x + 1, z + 1); }
            public boolean protectedColumn(int x, int z) { return runtime.protectedAt(r.world, new BlockPos(x, feet.getY(), z)); }
            public RtpPolicy.Cell cell(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                var state = chunk.getBlockState(pos);
                var shape = state.getCollisionShape(r.world, pos);
                return new RtpPolicy.Cell(state.isAir(), shape.isEmpty(),
                        Block.isShapeFullCube(shape) && state.isSolidBlock(r.world, pos), !state.getFluidState().isEmpty(),
                        RtpPolicy.hazardous(Registries.BLOCK.getId(state.getBlock()).toString()) || state.isIn(BlockTags.FIRE),
                        state.getBlock() instanceof FallingBlock || state.isIn(BlockTags.LEAVES));
            }
            public boolean collisionFree(int x, int y, int z) {
                Box body = r.player.getDimensions(EntityPose.STANDING).getBoxAt(x + 0.5, y, z + 0.5);
                // Oversized/changed poses must fit the air volume actually checked, not cause neighbor loads.
                return body.minY >= y && body.maxY <= y + 3
                        && RtpPolicy.containsBox(x - 1, z - 1, x + 2, z + 2, body.minX, body.minZ, body.maxX, body.maxZ)
                        && RtpService.insideBorder(r.world, body.minX, body.minZ, body.maxX, body.maxZ)
                        && r.world.isSpaceEmpty(r.player, body);
            }
        };
        return RtpPolicy.evaluate(terrain, spawn.getX(), spawn.getZ(), feet.getX(), feet.getY(), feet.getZ()) == RtpPolicy.Rejection.NONE;
    }

    private void releaseChunk(Request r) {
        if (r.chunk == null) return;
        ChunkPos owned = r.chunk;
        try { r.world.getChunkManager().removeTicket(ticket, owned, 0); }
        catch (RuntimeException error) {
            // An ambiguous release must not admit another ticket before the expiry fallback.
            // Pause this service until restart rather than weaken the one-ticket invariant.
            stopping = true;
            throw error;
        } finally { r.chunk = null; r.destination = null; }
    }

    private void finish(Request r, String message, long backoff) {
        if (active != r) return;
        r.valid.set(false); active = null;
        lastFinished = System.nanoTime(); admissionBackoff = backoff; hasFinished = true;
        try { releaseChunk(r); }
        catch (RuntimeException error) { WarLand.LOG.warn("RTP ticket release failed; bounded ticket expiry remains active", error); }
        // Do not cancel the Store's future: an already admitted transaction can commit.
        // storageWork remains a single global fence against queue growth until that work actually ends.
        if (message != null && runtime.online(r.player)) runtime.reply(r.player, message);
    }

    private static String cancellation(Request r, String message) {
        return r.stage == Stage.SAVING ? message + " Если ожидание уже сохранено, оно остаётся в силе; /rtp покажет остаток." : message;
    }

    static CompletableFuture<Long> readCooldown(Store store, UUID player, BooleanSupplier lease) {
        return store.submit(connection -> {
            if (!lease.getAsBoolean()) throw new CancellationException("RTP session expired before read");
            return RtpPolicy.decodeCooldown(Store.string(connection,
                    "SELECT json FROM state WHERE namespace=? AND key=?", COOLDOWN_NAMESPACE, player.toString()));
        });
    }

    static CompletableFuture<Reservation> reserveCooldown(Store store, UUID player, BooleanSupplier lease,
                                                           int chunkX, int chunkZ, LongSupplier clock) {
        return store.tx(lease, connection -> {
            long now = clock.getAsLong();
            long previous = RtpPolicy.decodeCooldown(Store.string(connection,
                    "SELECT json FROM state WHERE namespace=? AND key=?", COOLDOWN_NAMESPACE, player.toString()));
            if (RtpPolicy.remainingSeconds(previous, now) > 0) return new Reservation(false, previous);
            // Direct, serialized DB fence additionally catches a claim whose snapshot hasn't published yet.
            if (Store.scalar(connection, "SELECT COUNT(*) FROM claims WHERE dimension=? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?",
                    RtpPolicy.WORLD, chunkX - 1, chunkX + 1, chunkZ - 1, chunkZ + 1) != 0)
                throw new IllegalStateException("RTP destination is claimed or adjacent to a claim");
            long until = RtpPolicy.deadline(now);
            Store.update(connection, "INSERT INTO state(namespace,key,json) VALUES(?,?,?) ON CONFLICT(namespace,key) DO UPDATE SET json=excluded.json",
                    COOLDOWN_NAMESPACE, player.toString(), Long.toString(until));
            return new Reservation(true, until);
        });
    }
}
