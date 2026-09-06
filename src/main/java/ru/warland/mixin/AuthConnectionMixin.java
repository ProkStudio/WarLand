package ru.warland.mixin;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.auth.AuthPacketGate;
@Mixin(value=ClientConnection.class, priority=3000)
public abstract class AuthConnectionMixin {
 @Shadow private PacketListener packetListener;
 @Inject(method="channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at=@At("HEAD"), cancellable=true)
 private void warland$inbound(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
  if (AuthPacketGate.reject(packet, packetListener)) ci.cancel();
 }
}
