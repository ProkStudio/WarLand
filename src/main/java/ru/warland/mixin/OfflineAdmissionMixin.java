package ru.warland.mixin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import ru.warland.auth.*;
/** Extend the legacy policy only for the explicit dedicated encrypted-offline transport. */
@Mixin(value=AuthRuntime.class,remap=false)
public abstract class OfflineAdmissionMixin {
 @Redirect(method="configure",at=@At(value="INVOKE",target="Lru/warland/auth/RuntimePolicy;secure(ZZ)Z"))
 private boolean warland$transportPolicy(boolean online,boolean encrypted,ServerConfigurationNetworkHandler handler,MinecraftServer server) {
  return RuntimePolicy.secure(online,encrypted)||(!online&&encrypted&&OfflineTransport.enabled(server));
 }
}
