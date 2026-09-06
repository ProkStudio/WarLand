package ru.warland.auth;
import net.minecraft.server.MinecraftServer;
/** Explicit JVM startup choice. The normal server identity mode is not mutated. */
public final class OfflineTransport {
 public static final String OPTION="warland.encryptedOffline";
 private OfflineTransport() {}
 public static boolean enabled(MinecraftServer server) {
  return server!=null&&OfflinePolicy.enabled(server.isOnlineMode(),server.isDedicated(),Boolean.getBoolean(OPTION));
 }
}
