package ru.warland.mixin;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket;
import net.minecraft.network.packet.c2s.login.LoginKeyC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.auth.OfflineTransport;
/** Uses vanilla nonce verification and RSA/AES setup, not a synthetic session service. */
@Mixin(ServerLoginNetworkHandler.class)
public abstract class OfflineLoginMixin {
 @Shadow @Final MinecraftServer server;
 @Shadow @Final ClientConnection connection;
 @Shadow String profileName;
 @Shadow abstract void startVerify(GameProfile profile);
 @Shadow public abstract void disconnect(Text reason);
 @Unique private boolean warland$offlineHandshake;
 @Redirect(method="onHello",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;isOnlineMode()Z"))
 private boolean warland$requireEncryption(MinecraftServer s) {
  warland$offlineHandshake=OfflineTransport.enabled(s);
  return s.isOnlineMode()||warland$offlineHandshake;
 }
 @ModifyArg(method="onHello",at=@At(value="INVOKE",target="Lnet/minecraft/network/packet/s2c/login/LoginHelloS2CPacket;<init>(Ljava/lang/String;[B[BZ)V"),index=3)
 private boolean warland$identityProvider(boolean original) {return warland$offlineHandshake?false:original;}
 @Inject(method="onKey",at=@At(value="INVOKE",target="Lnet/minecraft/network/ClientConnection;setupEncryption(Ljavax/crypto/Cipher;Ljavax/crypto/Cipher;)V",shift=At.Shift.AFTER),cancellable=true)
 private void warland$verifyOfflineAccount(LoginKeyC2SPacket packet,CallbackInfo ci) {
  if(!warland$offlineHandshake)return;
  ci.cancel();warland$offlineHandshake=false;
  if(!OfflineTransport.enabled(server)||!connection.isEncrypted()){disconnect(Text.literal("WarLand: защищённое соединение недоступно."));return;}
  GameProfile profile=Uuids.getOfflinePlayerProfile(profileName);
  server.execute(()->{
   if(!connection.isOpen()||!OfflineTransport.enabled(server))return;
   // A password-less duplicate login must not evict an already playing account.
   if(server.getPlayerManager().getPlayer(profile.id())!=null){disconnect(Text.literal("WarLand: этот аккаунт уже подключён."));return;}
   startVerify(profile); // Only CONFIGURATION follows; no player/profile/grant before WarLand auth.
  });
 }
}
