package ru.warland.api;

import net.minecraft.server.MinecraftServer;

public interface Feature {
    void initialize(WarLandApi api);
    default void serverStarted(MinecraftServer server) {}
    default void tick(MinecraftServer server) {}
    default void stopped() {}
}
