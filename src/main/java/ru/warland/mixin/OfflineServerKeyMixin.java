package ru.warland.mixin;
import java.security.KeyPair;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.auth.*;
import ru.warland.core.CoreRuntime;
@Mixin(MinecraftServer.class)
public abstract class OfflineServerKeyMixin {
 @Shadow private KeyPair keyPair;
 @Inject(method="generateKeyPair",at=@At("HEAD"),cancellable=true)
 private void warland$persistentIdentity(CallbackInfo ci) {
  if(!OfflineTransport.enabled((MinecraftServer)(Object)this))return;
  try {
   java.nio.file.Path dir=CoreRuntime.INSTANCE.dataDirectory();java.nio.file.Files.createDirectories(dir);
   keyPair=ServerIdentity.loadOrCreate(dir.resolve("server-identity"));
   ru.warland.WarLand.LOG.info("WarLand encrypted-offline server fingerprint: {}",ServerIdentity.fingerprint(keyPair.getPublic()));
   ci.cancel();
  } catch(Exception unavailable){throw new IllegalStateException("WarLand private server identity unavailable; refusing startup",unavailable);}
 }
}
