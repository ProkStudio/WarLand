package ru.warland.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.explosion.ExplosionImpl;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.warland.core.CoreRuntime;

@Mixin(ExplosionImpl.class)
public abstract class ExplosionMixin {
    @Shadow @Final private ServerWorld world;
    @Inject(method="getBlocksToDestroy",at=@At("RETURN"),cancellable=true)
    private void warland$filter(CallbackInfoReturnable<List<BlockPos>> cir) {
        CoreRuntime runtime=CoreRuntime.INSTANCE;
        if(runtime==null)return;
        List<BlockPos> allowed=new ArrayList<>();
        for(BlockPos pos:cir.getReturnValue()) {
            if(runtime.ready()&&!runtime.protectedAt(world,pos))allowed.add(pos);
        }
        // Vanilla is allowed to shuffle/mutate its destruction list.
        cir.setReturnValue(allowed);
    }
}
