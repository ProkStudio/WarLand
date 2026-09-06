package ru.warland.mixin;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.auth.ServerPin;
/** Reject before sending any key/password if an offline server is not pre-pinned. */
@Mixin(ClientLoginNetworkHandler.class)
public abstract class OfflineClientPinMixin {
 @Shadow @Final private ClientConnection connection;
 @Inject(method="onHello",at=@At("HEAD"),cancellable=true)
 private void warland$checkIdentity(LoginHelloS2CPacket packet,CallbackInfo ci) {
  var pin=FabricLoader.getInstance().getConfigDir().resolve("warland/server-fingerprint.txt");
  if(packet.needsAuthentication()&&!java.nio.file.Files.exists(pin,java.nio.file.LinkOption.NOFOLLOW_LINKS))return;
  boolean trusted=false;
  try {trusted=ServerPin.matches(pin,packet.getPublicKey());}catch(Exception ignored){}
  if(!trusted){ci.cancel();connection.disconnect(Text.literal("WarLand: ключ сервера не подтверждён. Установите актуальный клиентский комплект сервера."));}
 }
}
