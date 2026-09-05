package ru.warland.mixin;

import net.minecraft.item.*;
import net.minecraft.block.BedBlock;
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
    private void warland$place(ItemPlacementContext context,CallbackInfoReturnable<ActionResult> cir) {
        CoreRuntime runtime=CoreRuntime.INSTANCE;
        if(runtime==null||!(context.getPlayer() instanceof ServerPlayerEntity player)||!(context.getWorld() instanceof ServerWorld world))return;
        boolean allowed=runtime.canBuild(player,world,context.getBlockPos());
        BlockItem item=(BlockItem)(Object)this;
        if(item.getBlock() instanceof BedBlock)allowed&=runtime.canBuild(player,world,context.getBlockPos().offset(context.getHorizontalPlayerFacing()));
        if(!allowed)cir.setReturnValue(ActionResult.FAIL);
    }
}
