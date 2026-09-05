package ru.warland.core;

import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.*;

public final class Protection {
 private Protection(){}
 public static void register(CoreRuntime r){
  PlayerBlockBreakEvents.BEFORE.register((w,p,pos,state,entity)->!(p instanceof ServerPlayerEntity sp)||r.canBuild(sp,w,pos));
  UseBlockCallback.EVENT.register((p,w,hand,hit)->{
   if(!(p instanceof ServerPlayerEntity sp)||!(w instanceof ServerWorld sw))return ActionResult.PASS;
   return r.canBuild(sp,sw,hit.getBlockPos())&&r.canBuild(sp,sw,hit.getBlockPos().offset(hit.getSide()))?ActionResult.PASS:ActionResult.FAIL;
  });
  AttackBlockCallback.EVENT.register((p,w,hand,pos,dir)->p instanceof ServerPlayerEntity sp&&w instanceof ServerWorld sw&&!r.canBuild(sp,sw,pos)?ActionResult.FAIL:ActionResult.PASS);
  UseEntityCallback.EVENT.register((p,w,hand,entity,hit)->p instanceof ServerPlayerEntity sp&&w instanceof ServerWorld sw&&!r.canBuild(sp,sw,entity.getBlockPos())?ActionResult.FAIL:ActionResult.PASS);
  AttackEntityCallback.EVENT.register((p,w,hand,entity,hit)->p instanceof ServerPlayerEntity sp&&!r.canDamage(sp,entity)?ActionResult.FAIL:ActionResult.PASS);
  UseItemCallback.EVENT.register((p,w,hand)->{
   if(!(p instanceof ServerPlayerEntity sp)||!(w instanceof ServerWorld sw))return ActionResult.PASS;
   if(!r.ready())return ActionResult.FAIL;
   HitResult hit=p.raycast(6,0,false);
   if(hit instanceof BlockHitResult b&&!r.canBuild(sp,sw,b.getBlockPos())&&(p.getStackInHand(hand).getItem() instanceof net.minecraft.item.BucketItem||p.getStackInHand(hand).getItem() instanceof net.minecraft.item.FluidModificationItem))return ActionResult.FAIL;
   return ActionResult.PASS;
  });
  ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,source,amount)->{
   if(!(entity.getEntityWorld() instanceof ServerWorld world))return true;
   if(!r.ready()||r.safe(world,entity.getBlockPos()))return false;
   if(source.getAttacker() instanceof ServerPlayerEntity p){if(!r.canDamage(p,entity))return false;r.tag(p.getUuid());}
   if(entity instanceof ServerPlayerEntity p)r.tag(p.getUuid());return true;
  });
 }
}
