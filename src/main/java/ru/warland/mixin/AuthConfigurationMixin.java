package ru.warland.mixin;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.core.CoreRuntime;
/** Independent barrier for both client READY and server configuration completion. */
@Mixin(value=ServerConfigurationNetworkHandler.class, priority=3000)
public abstract class AuthConfigurationMixin {
 @Inject(method={"onReady", "endConfiguration"}, at=@At("HEAD"), cancellable=true)
 private void warland$configurationBarrier(CallbackInfo ci) {
  var handler=(ServerConfigurationNetworkHandler)(Object)this;
  CoreRuntime runtime=CoreRuntime.INSTANCE;
  if(runtime==null||!runtime.auth.configurationReleased(handler)) {
   ci.cancel(); handler.disconnect(Text.literal("WarLand: завершите защищённую авторизацию перед входом."));
  }
 }
}
