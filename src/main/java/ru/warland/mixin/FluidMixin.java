package ru.warland.mixin;
import net.minecraft.fluid.*;
import net.minecraft.block.BlockState;
import net.minecraft.world.WorldAccess;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.core.CoreRuntime;
@Mixin(FlowableFluid.class)
public abstract class FluidMixin {
 @Inject(method="flow",at=@At("HEAD"),cancellable=true)
 private void warland$flow(WorldAccess world,BlockPos pos,BlockState state,Direction direction,FluidState fluid,CallbackInfo ci){var r=CoreRuntime.INSTANCE;if(r==null||!(world instanceof ServerWorld w))return;BlockPos from=pos.offset(direction.getOpposite());String d=w.getRegistryKey().getValue().toString();String a=r.nations.claim(d,from.getX()>>4,from.getZ()>>4),b=r.nations.claim(d,pos.getX()>>4,pos.getZ()>>4);if(!r.ready()||r.safe(w,pos)||!java.util.Objects.equals(a,b))ci.cancel();}
}
