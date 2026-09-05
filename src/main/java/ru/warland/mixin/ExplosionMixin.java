package ru.warland.mixin;
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
 private void warland$filter(CallbackInfoReturnable<List<BlockPos>> cir){var r=CoreRuntime.INSTANCE;if(r!=null)cir.setReturnValue(cir.getReturnValue().stream().filter(p->r.ready()&&!r.protectedAt(world,p)).toList());}
}
