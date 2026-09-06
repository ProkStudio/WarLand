package ru.warland.mixin;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.core.CoreRuntime;
@Mixin(ItemEntity.class)
public abstract class AuthPickupMixin {
 @Inject(method="onPlayerCollision", at=@At("HEAD"), cancellable=true)
 private void warland$pickup(PlayerEntity player, CallbackInfo ci) {
  CoreRuntime r=CoreRuntime.INSTANCE;
  if(r!=null&&player instanceof ServerPlayerEntity p&&!r.authorized(p))ci.cancel();
 }
}
