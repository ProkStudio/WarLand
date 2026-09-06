package ru.warland.mixin;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.core.CoreRuntime;
@Mixin(value=ServerPlayerEntity.class, priority=3000)
public abstract class AuthPlayerMixin {
 @Inject(method={"tick", "playerTick", "onDeath", "dropSelectedItem"}, at=@At("HEAD"), cancellable=true)
 private void warland$noSimulation(CallbackInfo ci) {
  CoreRuntime r=CoreRuntime.INSTANCE;
  if(r!=null&&!r.authorized((ServerPlayerEntity)(Object)this))ci.cancel();
 }
}
