package ru.warland.mixin;
import net.minecraft.block.*;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.warland.core.CoreRuntime;
@Mixin(PistonBlock.class)
public abstract class PistonMixin {
 @Inject(method="isMovable",at=@At("HEAD"),cancellable=true)
 private static void warland$boundary(BlockState state,World world,BlockPos pos,Direction direction,boolean canBreak,Direction pistonDirection,CallbackInfoReturnable<Boolean> cir){
  var r=CoreRuntime.INSTANCE;if(r==null||!(world instanceof ServerWorld w))return;
  String d=w.getRegistryKey().getValue().toString();BlockPos to=pos.offset(direction);String a=r.nations.claim(d,pos.getX()>>4,pos.getZ()>>4),b=r.nations.claim(d,to.getX()>>4,to.getZ()>>4);
  if(!r.ready()||r.safe(w,pos)||r.safe(w,to)||!java.util.Objects.equals(a,b))cir.setReturnValue(false);
 }
}
