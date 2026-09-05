package ru.warland.mixin;
import net.minecraft.item.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.warland.core.CoreRuntime;
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
 @Inject(method="place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",at=@At("HEAD"),cancellable=true)
 private void warland$place(ItemPlacementContext context,CallbackInfoReturnable<ActionResult> cir){
  var r=CoreRuntime.INSTANCE;if(r!=null&&context.getPlayer() instanceof ServerPlayerEntity p&&context.getWorld() instanceof ServerWorld w&&!r.canBuild(p,w,context.getBlockPos()))cir.setReturnValue(ActionResult.FAIL);
 }
}
