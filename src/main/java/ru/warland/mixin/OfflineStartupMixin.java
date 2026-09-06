package ru.warland.mixin;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import ru.warland.auth.OfflineTransport;
import ru.warland.core.CoreRuntime;
/** Adapt only the legacy startup admission guard; do not change Minecraft online-mode. */
@Mixin(value=CoreRuntime.class,remap=false)
public abstract class OfflineStartupMixin {
 @Redirect(method="start",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;isOnlineMode()Z",remap=true))
 private boolean warland$supportedIdentityMode(MinecraftServer server) {
  return server.isOnlineMode()||OfflineTransport.enabled(server);
 }
}
