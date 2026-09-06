package ru.warland.mixin;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.warland.core.CoreRuntime;
import ru.warland.moderation.Moderation;
/** Also deny stored non-owner roles on callbacks from an expired connection. */
@Mixin(value=Moderation.class, remap=false)
public abstract class AuthModerationMixin {
 @Shadow @Final private CoreRuntime r;
 @Inject(method={"canModerate", "canView"}, at=@At("HEAD"), cancellable=true)
 private void warland$staff(ServerCommandSource source, CallbackInfoReturnable<Boolean> cir) {
  if(source.getEntity() instanceof ServerPlayerEntity p&&!r.online(p))cir.setReturnValue(false);
 }
 @Inject(method="canChat", at=@At("HEAD"), cancellable=true)
 private void warland$chat(ServerPlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
  if(!r.online(player))cir.setReturnValue(false);
 }
}
