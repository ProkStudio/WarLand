package ru.warland.api;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Stable integration boundary. Register Fabric callbacks during initialize, not at server start. */
public interface WarLandApi {
    record MenuEntry(ItemStack icon, Runnable action) {}
    MinecraftServer server();
    Path dataDirectory();
    boolean ready();
    boolean canBuild(ServerPlayerEntity player, ServerWorld world, BlockPos pos);
    boolean canDamage(ServerPlayerEntity player, Entity target);
    boolean inCombat(UUID player);
    boolean staff(ServerCommandSource source, String permission);
    void reply(ServerPlayerEntity player, String message);
    void menu(ServerPlayerEntity player, String title, List<MenuEntry> entries);
    void audit(String actor, String action, String target);
    CompletableFuture<Long> balance(UUID player);
    CompletableFuture<Boolean> debit(UUID player, long amount, String operationId, String reason);
    CompletableFuture<Boolean> credit(UUID player, long amount, String operationId, String reason);
    CompletableFuture<String> getState(String namespace, String key);
    CompletableFuture<Void> setState(String namespace, String key, String json);
    void setSafeSpawn(ServerWorld world, BlockPos feet, int radius);
    void registerWarp(String id, ServerWorld world, BlockPos feet);
}
