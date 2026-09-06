package ru.warland.mixin;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import ru.warland.core.CoreRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.auth.AuthPacketGate;
@Mixin(value=ServerCommonNetworkHandler.class, priority=3000)
public abstract class AuthCommonMixin {
 @Inject(method="onCustomClickAction", at=@At("HEAD"), cancellable=true)
 private void warland$dialogAction(CallbackInfo ci) {
  CoreRuntime r=CoreRuntime.INSTANCE;
  if(r!=null&&(!((Object)this instanceof ServerPlayNetworkHandler play)||!r.authorized(play.player)))ci.cancel();
 }
 @Inject(method="onCustomPayload", at=@At("HEAD"), cancellable=true)
 private void warland$payload(CustomPayloadC2SPacket packet, CallbackInfo ci) {
  if (AuthPacketGate.reject(packet, (ServerCommonNetworkHandler)(Object)this)) ci.cancel();
 }
}
