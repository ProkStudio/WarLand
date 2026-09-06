package ru.warland.rtp;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Source-level regression tripwires, not substitutes for in-game acceptance tests. */
class RtpWiringTest {
    String source() throws Exception { return Files.readString(Path.of("src/main/java/ru/warland/rtp/RtpService.java")); }
    @Test void noWorldEditsPublicWarpOrEconomicDebit() throws Exception {
        String s = source();
        for (String forbidden : new String[]{".setBlockState(", ".breakBlock(", ".setChunkForced(", ".registerWarp(",
                ".warps.put(", ".debit(", ".credit(", "Store.change(", "CREATE TABLE", "ALTER TABLE"})
            assertFalse(s.contains(forbidden), forbidden);
        assertTrue(s.contains("literal(\"rtp\")"));
    }
    @Test void loadPathIsPollingAndHasExplicitTicketCleanup() throws Exception {
        String s = source();
        for (String forbidden : new String[]{".getChunk(", ".getChunkFutureSyncOnMainThread(", ".join(",
                ".setSafeSpawn(", "CompletableFuture.runAsync", "new Thread("}) assertFalse(s.contains(forbidden), forbidden);
        assertTrue(s.contains(".addTicket(ticket, chunk, 0)")); assertTrue(s.contains(".getWorldChunk("));
        assertTrue(s.contains(".removeTicket(ticket, owned, 0)")); assertTrue(s.contains("CAN_EXPIRE_BEFORE_LOAD"));
        assertTrue(s.contains("RtpPolicy.LOAD_NANOS")); assertTrue(s.contains("RtpPolicy.SEARCH_NANOS"));
        assertTrue(s.contains("RtpPolicy.hasBudget("));
    }
    @Test void exactSessionIsCapturedAndRecheckedBeforeSavingAndMoving() throws Exception {
        String s = source();
        assertTrue(s.contains("BooleanSupplier capturedSession = runtime.auth.lease(player)"));
        assertTrue(s.contains("valid.get() && capturedSession.getAsBoolean()"));
        assertTrue(s.contains("runtime.online(r.player) && r.lease.getAsBoolean()"));
        assertTrue(s.contains("store.tx(lease, connection ->"));
        int requestStart = s.indexOf("private int request(");
        assertTrue(s.indexOf("!runtime.online(player)", requestStart) < s.indexOf("readCooldown(runtime.store", requestStart));
        int finalCheck = s.indexOf("if (!playerValid(r) || result.until()");
        int move = s.indexOf("r.player.teleport(");
        assertTrue(finalCheck > 0 && move > finalCheck);
        assertTrue(s.substring(finalCheck, move).contains("!destinationValid(r)"));
        assertFalse(s.contains("getPlayerManager().getPlayer("), "Never resolve a replacement session by UUID");
    }
    @Test void movementLogoutDamageWarAndConcurrentWorkAreGuarded() throws Exception {
        String s = source();
        for (String required : new String[]{"ServerPlayConnectionEvents.DISCONNECT", "SERVER_STOPPING",
                "ServerLivingEntityEvents.AFTER_DAMAGE", "source.getAttacker() == r.player", "runtime.inCombat(",
                "runtime.inventoryLocked(", "runtime.teleports.containsKey(", "runtime.wars.window(",
                "r.player.getEntityWorld() == r.originWorld", "RtpPolicy.moved(", "r.valid.set(false)",
                "storageWork != null && !storageWork.isDone()", "active != null"}) assertTrue(s.contains(required), required);
    }
    @Test void claimsFluidShapesSurfaceAndBorderAreWiredToWorldAdapter() throws Exception {
        String s = source();
        for (String required : new String[]{"runtime.protectedAt(", "runtime.nations.claim(", "SELECT COUNT(*) FROM claims",
                "Heightmap.Type.WORLD_SURFACE", "state.getFluidState().isEmpty()", "state.getCollisionShape(",
                "Block.isShapeFullCube(shape)", "BlockTags.LEAVES", "instanceof FallingBlock", "BlockTags.FIRE",
                "r.world.isSpaceEmpty(r.player, body)", "EntityPose.STANDING", "border.getBoundWest() + 1"})
            assertTrue(s.contains(required), required);
    }
}
