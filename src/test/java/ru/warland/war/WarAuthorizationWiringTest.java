package ru.warland.war;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-wiring regression guards complement real SQLite lease tests; not a live-client acceptance test. */
class WarAuthorizationWiringTest {
    private static final Path JAVA = Path.of("src/main/java/ru/warland");
    private static String source(String path) throws Exception { return Files.readString(JAVA.resolve(path)); }

    @Test void productionUsesLiveLeaseAndPlayerIdentityPolicies() throws Exception {
        String core = source("core/CoreRuntime.java"), service = source("war/WarService.java");
        assertTrue(core.contains("new WarService(store,config,nations,this::actionLease,this::online)"));
        assertTrue(service.contains("new WarRepository(db, config, System::currentTimeMillis, actorLeases)"));
        assertTrue(core.contains("!server.isOnThread()"));
        assertTrue(core.contains("server.getPlayerManager().getPlayer(p.getUuid())==p"));
        String auth = source("auth/AuthRuntime.java");
        String lease = auth.substring(auth.indexOf("public BooleanSupplier lease("), auth.indexOf("public void profileReady("));
        assertTrue(lease.contains("if (!allowed(player)) return () -> false"));
        assertTrue(lease.contains("sessions.get(c) == s"));
        assertTrue(lease.contains("s.profileReady && engine.authenticated(s.identity)"));
    }

    @Test void unauthorizedPlayersAreFilteredBeforeAllParticipationSnapshots() throws Exception {
        String service = source("war/WarService.java");
        int loop = service.indexOf("for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())");
        int gate = service.indexOf("if (!authorized.test(player)) continue;", loop);
        assertTrue(loop >= 0 && gate > loop);
        for (String step : new String[]{"nations.member(player.getUuid())", "players.add(new Combatant", "online.merge(", "occupants.computeIfAbsent("}) {
            int operation = service.indexOf(step, loop);
            assertTrue(operation > gate, step + " must follow session authorization");
        }
        assertTrue(service.contains("progress.retain(active)"));
    }

    @Test void repositoryCapturesOnceOnEntryAndDelegatesGuardToWorker() throws Exception {
        String repository = source("war/WarRepository.java");
        int start = repository.indexOf("private <T> CompletableFuture<T> action(");
        int end = repository.indexOf("CompletableFuture<Void> initialize()", start);
        assertTrue(start >= 0 && end > start);
        String action = repository.substring(start, end);
        assertTrue(action.contains("actorLeases.apply(actor)"));
        assertTrue(action.contains("return db.tx(lease, work)"));
        assertFalse(action.contains("db.tx(() ->"));
        assertTrue(action.contains("CompletableFuture.failedFuture(error)"));
    }

    @Test void everyUserMutationUsesTheSameGuardedBoundary() throws Exception {
        String repository = source("war/WarRepository.java");
        assertTrue(repository.contains("declare(UUID player, String target) {\n        return action(player, c -> {"));
        assertTrue(repository.contains("peace(UUID player, String targetName) {\n        return action(player, c -> {"));
        assertTrue(repository.contains("surrender(UUID player, String targetName) {\n        return action(player, c -> {"));
        assertTrue(repository.contains("capture(CaptureKey key, UUID participant) {\n        return action(participant, c -> {"));
        assertTrue(repository.contains("CompletableFuture<Void> initialize() {\n        return db.tx(c -> {"));
        assertTrue(repository.contains("CompletableFuture<Void> settle(String warId) { return db.tx(c ->"));
    }

    @Test void adapterDoesNotExposeItsDurablePublicationFutureOrAnAllowingDefault() throws Exception {
        String service = source("war/WarService.java");
        assertTrue(service.contains("this(db, config, nations, actor -> () -> false, player -> false)"));
        assertTrue(service.contains("return result.thenCompose(message -> refresh().thenApply(v -> message)).copy()"));
        assertTrue(service.contains("repository.capture(key, entry.getValue()).thenCompose(v -> nations.refresh()).thenCompose(v -> refresh())"));
    }
}
