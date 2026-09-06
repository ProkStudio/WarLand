package ru.warland.auth;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.*;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.server.network.*;
import ru.warland.core.CoreRuntime;

/** Earliest inbound gate, repeated at handler HEAD after main-thread packet queueing. */
public final class AuthPacketGate {
 private AuthPacketGate() {}
 public static boolean reject(Packet<?> packet, PacketListener listener) {
  CoreRuntime r = CoreRuntime.INSTANCE;
  if (r == null) return false; // Physical client: no logical WarLand server runtime.
  if (listener instanceof ServerPlayNetworkHandler play) {
   if (packet instanceof CommandExecutionC2SPacket p && RuntimePolicy.credentialCommand(p.command())) return true;
   if (packet instanceof ChatCommandSignedC2SPacket p && RuntimePolicy.credentialCommand(p.command())) return true;
   if (packet instanceof RequestCommandCompletionsC2SPacket p && RuntimePolicy.credentialCommand(p.getPartialCommand())) return true;
   boolean control = packet instanceof KeepAliveC2SPacket || packet instanceof CommonPongC2SPacket
       || packet instanceof ClientOptionsC2SPacket || packet instanceof PlayerLoadedC2SPacket
       || packet instanceof TeleportConfirmC2SPacket || packet instanceof AcknowledgeChunksC2SPacket
       || packet instanceof PlayerSessionC2SPacket;
   return control ? !r.auth.authenticated(play.player) : !r.authorized(play.player);
  }
  if (listener instanceof ServerConfigurationNetworkHandler && packet instanceof CustomClickActionC2SPacket) return true;
  if (listener instanceof ServerConfigurationNetworkHandler config && packet instanceof CustomPayloadC2SPacket p) {
   if (r.auth.configurationReleased(config)) return false;
   var id = p.payload().getId().id();
   boolean allowed = id.equals(AuthPayloads.Request.ID.id()) || id.toString().equals("minecraft:register") || id.toString().equals("minecraft:unregister");
   if (!allowed && p.payload() instanceof AuthPayloads.Request request) request.close();
   return !allowed;
  }
  return false;
 }
}
