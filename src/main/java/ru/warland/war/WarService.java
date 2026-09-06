package ru.warland.war;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import ru.warland.WarLand;
import ru.warland.core.GameConfig;
import ru.warland.data.Store;
import ru.warland.nations.NationsService;
import ru.warland.war.WarRules.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Minecraft adapter. JDBC authority and testable rules live in the war package. */
public final class WarService {
    public record War(String id, String attacker, String defender, long declared, long starts, long ends,
                      String status, int attackScore, int defendScore) {}
    private record Location(String dimension, int x, int z) {}
    private record Combatant(UUID id, String nation, Location location, boolean centered) {}
    private final GameConfig config;
    private final NationsService nations;
    private final WarRepository repository;
    private final Predicate<ServerPlayerEntity> authorized;
    private volatile WarRepository.Snapshot cache = new WarRepository.Snapshot(List.of(), Map.of());
    private final CaptureProgress progress = new CaptureProgress();
    private final Set<CaptureKey> capturing = new HashSet<>();
    private final Set<String> settling = new HashSet<>();

    /** Compatibility constructor: without explicit live authorization, all player actions fail closed. */
    public WarService(Store db, GameConfig config, NationsService nations) {
        this(db, config, nations, actor -> () -> false, player -> false);
    }

    public WarService(Store db, GameConfig config, NationsService nations,
                      Function<UUID, BooleanSupplier> actorLeases,
                      Predicate<ServerPlayerEntity> authorized) {
        this.config = config; this.nations = nations;
        this.authorized = Objects.requireNonNull(authorized, "player authorization");
        repository = new WarRepository(db, config, System::currentTimeMillis, actorLeases);
    }
    public List<War> wars() { return cache.wars(); }
    public String windowLabel(War war) { Schedule value=cache.schedules().get(war.id()); return value==null?"Расписание недоступно":value.label(); }
    public boolean window(War w, long now) {
        Schedule schedule = cache.schedules().get(w.id());
        return config.enableWarCapture && w.status().equals("ACTIVE") && schedule != null && schedule.open(now, w.starts(), w.ends());
    }
    public String phase(War w, long now) {
        if (!w.status().equals("ACTIVE")) return w.status();
        if (now < w.starts()) return "MOBILIZATION";
        if (now >= w.ends()) return "ENDING";
        return window(w, now) ? "ACTIVE" : "TRUCE";
    }
    public boolean enemies(String a, String b) {
        if (a == null || b == null || a.equals(b)) return false;
        long now = System.currentTimeMillis();
        return wars().stream().anyMatch(w -> window(w, now) && ((w.attacker().equals(a) && w.defender().equals(b)) || (w.attacker().equals(b) && w.defender().equals(a))));
    }
    public CompletableFuture<Void> refresh() { return repository.snapshot().thenAccept(snapshot -> cache = snapshot); }
    public CompletableFuture<Void> initialize() { return repository.initialize().thenCompose(v -> refresh()); }
    public CompletableFuture<String> declare(UUID player, String target) { return update(repository.declare(player, target)); }
    /** First invocation offers peace, the opposite side's invocation accepts it within ten minutes. */
    public CompletableFuture<String> peace(UUID player, String target) { return update(repository.peace(player, target)); }
    /** Call only after the command/UI's explicit destructive-action confirmation. */
    public CompletableFuture<String> surrender(UUID player, String target) { return update(repository.surrender(player, target)); }
    private CompletableFuture<String> update(CompletableFuture<String> result) {
        // A cancelled observer must not suppress publication of an already committed mutation.
        return result.thenCompose(message -> refresh().thenApply(v -> message)).copy();
    }

    /** Once per second; snapshots only, no chunk loading/scanning and no JDBC on the server thread. */
    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        List<Combatant> players = new ArrayList<>();
        Map<String, Integer> online = new HashMap<>();
        Map<Location, Set<String>> occupants = new HashMap<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            // Pending/revoked sessions must not affect capture, contest or offline-protection counts.
            if (!authorized.test(player)) continue;
            var member = nations.member(player.getUuid());
            if (member == null || !player.isAlive() || player.isCreative() || player.isSpectator()) continue;
            var pos = player.getBlockPos();
            String dimension = player.getEntityWorld().getRegistryKey().getValue().toString();
            Location location = new Location(dimension, pos.getX() >> 4, pos.getZ() >> 4);
            boolean centered = Math.abs((pos.getX() & 15) - 8) <= 4 && Math.abs((pos.getZ() & 15) - 8) <= 4;
            players.add(new Combatant(player.getUuid(), member.nation(), location, centered));
            online.merge(member.nation(), 1, Integer::sum);
            occupants.computeIfAbsent(location, k -> new HashSet<>()).add(member.nation());
        }
        Set<CaptureKey> active = new HashSet<>();
        for (War war : wars()) {
            if (!war.status().equals("ACTIVE")) continue;
            if (now >= war.ends()) { settle(war, server); continue; }
            if (!window(war, now)) continue;
            Schedule schedule = cache.schedules().get(war.id());
            Map<CaptureKey, UUID> candidates = new HashMap<>();
            for (Combatant player : players) {
                String side = player.nation();
                if (!player.centered() || (!side.equals(war.attacker()) && !side.equals(war.defender()))) continue;
                Location at = player.location();
                if (!at.dimension().equals("minecraft:overworld")) continue;
                String opponent = side.equals(war.attacker()) ? war.defender() : war.attacker();
                if (!opponent.equals(nations.claim(at.dimension(), at.x(), at.z()))) continue;
                var capital = nations.snapshot().nations().get(opponent);
                if (capital == null || (capital.dimension().equals(at.dimension()) && (capital.x() >> 4) == at.x() && (capital.z() >> 4) == at.z())) continue;
                if (!side.equals(nations.claim(at.dimension(), at.x() + 1, at.z())) && !side.equals(nations.claim(at.dimension(), at.x() - 1, at.z()))
                        && !side.equals(nations.claim(at.dimension(), at.x(), at.z() + 1)) && !side.equals(nations.claim(at.dimension(), at.x(), at.z() - 1))) continue;
                if (occupants.getOrDefault(at, Set.of()).contains(opponent)) continue;
                CaptureKey key = new CaptureKey(war.id(), side, at.dimension(), at.x(), at.z(), schedule.windowKey(now));
                candidates.putIfAbsent(key, player.id());
            }
            for (var entry : candidates.entrySet()) {
                CaptureKey key = entry.getKey();
                String opponent = key.side().equals(war.attacker()) ? war.defender() : war.attacker();
                active.add(key);
                if (!capturing.contains(key) && progress.advance(key, online.getOrDefault(opponent, 0) > 0, now)) {
                    capturing.add(key);
                    repository.capture(key, entry.getValue()).thenCompose(v -> nations.refresh()).thenCompose(v -> refresh())
                            .whenComplete((v, error) -> server.execute(() -> {
                                capturing.remove(key); progress.remove(key);
                                if (error == null) server.getPlayerManager().broadcast(Text.literal("[WarLand] Захвачена пограничная цель: " + key.x() + ", " + key.z()), false);
                                else WarLand.LOG.debug("War capture rejected: {}", key, error);
                            }));
                }
            }
        }
        // Also clears on disabled capture, a closed window, peace, contest, logout or changed ownership.
        progress.retain(active);
    }
    private void settle(War war, MinecraftServer server) {
        if (!settling.add(war.id())) return;
        repository.settle(war.id()).thenCompose(v -> refresh()).whenComplete((v, error) -> server.execute(() -> {
            settling.remove(war.id());
            if (error != null) WarLand.LOG.error("War settlement failed: {}", war.id(), error);
        }));
    }
}
