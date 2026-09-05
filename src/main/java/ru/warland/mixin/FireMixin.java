package ru.warland.mixin;
import net.minecraft.block.FireBlock;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.warland.core.CoreRuntime;
@Mixin(FireBlock.class)
public abstract class FireMixin {
 @Inject(method="trySpreadingFire",at=@At("HEAD"),cancellable=true)
 private void warland$fire(World world,BlockPos pos,int spreadFactor,Random random,int age,CallbackInfo ci){var r=CoreRuntime.INSTANCE;if(r!=null&&world instanceof ServerWorld w&&(!r.ready()||r.protectedAt(w,pos)))ci.cancel();}
}
