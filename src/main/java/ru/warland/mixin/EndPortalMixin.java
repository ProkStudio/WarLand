package ru.warland.mixin;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(EndPortalBlock.class)
public abstract class EndPortalMixin {
 @Inject(method="createTeleportTarget",at=@At("HEAD"),cancellable=true)
 private void warland$noEnd(ServerWorld world,Entity entity,BlockPos pos,CallbackInfoReturnable<TeleportTarget> cir){cir.setReturnValue(null);}
}
