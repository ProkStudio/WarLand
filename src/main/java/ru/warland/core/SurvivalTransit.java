package ru.warland.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import ru.warland.WarLand;

/** Finds one surface candidate per second; never edits the survival world. */
public final class SurvivalTransit {
    private static final int[] OFFSETS={0,64,-64,128,-128,192,-192,256,-256};
    private final CoreRuntime runtime;
    private int ticks,candidate;
    private boolean complete;
    private SurvivalTransit(CoreRuntime runtime){this.runtime=runtime;}
    public static void register(CoreRuntime runtime){SurvivalTransit transit=new SurvivalTransit(runtime);ServerTickEvents.END_SERVER_TICK.register(transit::tick);}
    private void tick(MinecraftServer server){
        if(complete||!runtime.ready()||++ticks%20!=0)return;
        if(candidate>=OFFSETS.length*OFFSETS.length){complete=true;WarLand.LOG.error("No safe survival transfer point found; manual transport configuration required");return;}
        ServerWorld world=server.getOverworld();BlockPos center=world.getSpawnPoint().getPos();
        int x=center.getX()+OFFSETS[candidate%OFFSETS.length],z=center.getZ()+OFFSETS[candidate/OFFSETS.length];candidate++;
        BlockPos feet=new BlockPos(x,world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,x,z),z);
        if(!world.getWorldBorder().contains(feet)||runtime.nations.claim(world.getRegistryKey().getValue().toString(),x>>4,z>>4)!=null)return;
        if(!world.getBlockState(feet).isAir()||!world.getBlockState(feet.up()).isAir()||!world.getBlockState(feet.down()).isSolidBlock(world,feet.down())||!world.getFluidState(feet.down()).isEmpty())return;
        var floor=world.getBlockState(feet.down());if(floor.isOf(Blocks.MAGMA_BLOCK)||floor.isOf(Blocks.CACTUS))return;
        // This protects the arrival without overwriting the actual hub spawn warp.
        runtime.safeZones.add(new CoreRuntime.SafeZone(world,feet.toImmutable(),12));
        runtime.registerWarp("survival",world,feet);complete=true;
        WarLand.LOG.info("Safe survival transit registered; no blocks modified");
    }
}
