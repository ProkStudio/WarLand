package ru.warland.combat;

import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.*;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.*;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.*;
import ru.warland.api.*;
import ru.warland.core.CoreRuntime;
import ru.warland.ui.Menus;

/** First combat slice: one rifle, server raycasts and full disposable magazines. */
public final class CombatFeature implements Feature {
    public static Item RIFLE,MAGAZINE;
    private static CombatFeature instance;
    private WarLandApi api;
    private long tick;
    private final Map<UUID,Long> nextShot=new HashMap<>();
    private final Map<UUID,Reload> reloads=new HashMap<>();
    private record Reload(int slot,String serial,long due){}
    public CombatFeature(){}
    public static synchronized void registerItems(){
        if(RIFLE!=null)return;
        var gunKey=RegistryKey.of(RegistryKeys.ITEM,Identifier.of("warland","ak74"));
        RIFLE=Registry.register(Registries.ITEM,gunKey,new RifleItem(new Item.Settings().registryKey(gunKey).maxCount(1)));
        var magazineKey=RegistryKey.of(RegistryKeys.ITEM,Identifier.of("warland","magazine_545"));
        MAGAZINE=Registry.register(Registries.ITEM,magazineKey,new Item(new Item.Settings().registryKey(magazineKey).maxCount(16)));
    }
    @Override public void initialize(WarLandApi api){
        registerItems();this.api=api;instance=this;
        CommandRegistrationCallback.EVENT.register((d,r,e)->{
            d.register(CommandManager.literal("arsenal").executes(c->{menu(c.getSource().getPlayerOrThrow());return 1;}));
            d.register(CommandManager.literal("armory").executes(c->{menu(c.getSource().getPlayerOrThrow());return 1;}));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{reloads.remove(h.player.getUuid());nextShot.remove(h.player.getUuid());});
    }
    private void menu(ServerPlayerEntity player){
        api.menu(player,"Арсенал · АК-74",List.of(
            new WarLandApi.MenuEntry(Menus.icon(RIFLE,"АК-74","ПКМ — одиночный выстрел","Shift + ПКМ — перезарядка 2,2 с","30 патронов, дальность 80 блоков"),()->api.reply(player,"АК-74 создаётся на верстаке. Новый автомат пуст: нужен магазин 5,45.")),
            new WarLandApi.MenuEntry(Menus.icon(MAGAZINE,"Магазин 5,45 · 30 патронов","При перезарядке старый остаток теряется","Перенос автомата в другой слот отменяет перезарядку"),()->api.reply(player,"Полный магазин: ряд железных самородков / ряд пороха / ряд железных самородков.")),
            new WarLandApi.MenuEntry(Menus.icon(Items.SHIELD,"Правила огня","Проверки урона выполняет сервер","Стены и безопасные территории учитываются","Выстрел включает боевой таймер"),()->api.reply(player,"Обычное PvP разрешено только в пустоши. Оружие не обходит правила территорий."))));
    }
    private boolean eligible(ServerPlayerEntity p){return api.ready()&&p.isAlive()&&!p.isSpectator()&&!p.isCreative()&&!p.hasVehicle()&&!api.inventoryLocked(p.getUuid());}
    private static int ammo(ItemStack stack){return WeaponRules.ammunition(stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getInt("warland_ammo",0));}
    private static String serial(ItemStack stack){return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getString("warland_gun","");}
    private static void setAmmo(ItemStack stack,int value){NbtComponent.set(DataComponentTypes.CUSTOM_DATA,stack,n->n.putInt("warland_ammo",WeaponRules.ammunition(value)));}
    private ActionResult use(ServerPlayerEntity p,Hand hand){
        if(hand!=Hand.MAIN_HAND||!eligible(p))return ActionResult.FAIL;
        ItemStack stack=p.getStackInHand(hand);
        if(serial(stack).isEmpty())NbtComponent.set(DataComponentTypes.CUSTOM_DATA,stack,n->n.putString("warland_gun",UUID.randomUUID().toString()));
        if(p.isSneaking()){reload(p,stack);return ActionResult.SUCCESS;}
        if(reloads.containsKey(p.getUuid()))return ActionResult.FAIL;
        int rounds=ammo(stack);
        if(!WeaponRules.mayFire(tick,nextShot.getOrDefault(p.getUuid(),0L),rounds,false)){
            if(rounds==0)p.sendMessage(Text.literal("Магазин пуст. Shift + ПКМ — перезарядка."),true);
            return ActionResult.FAIL;
        }
        nextShot.put(p.getUuid(),tick+WeaponRules.SHOT_TICKS);setAmmo(stack,rounds-1);
        p.getItemCooldownManager().set(stack,WeaponRules.SHOT_TICKS);p.getInventory().markDirty();p.currentScreenHandler.sendContentUpdates();
        if(api instanceof CoreRuntime core)core.tag(p.getUuid());
        ServerWorld world=p.getEntityWorld();Vec3d from=p.getEyePos(),direction=p.getRotationVec(1),end=from.add(direction.multiply(WeaponRules.RANGE));
        var wall=world.raycast(new RaycastContext(from,end,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,p));
        if(wall.getType()!=HitResult.Type.MISS)end=wall.getPos();
        double distance=from.squaredDistanceTo(end);
        var hit=ProjectileUtil.raycast(p,from,end,p.getBoundingBox().stretch(direction.multiply(WeaponRules.RANGE)).expand(1),entity->entity!=p&&entity.isAlive()&&!entity.isSpectator()&&entity.canHit(),distance);
        if(hit!=null){end=hit.getPos();if(api.canDamage(p,hit.getEntity()))hit.getEntity().damage(world,world.getDamageSources().playerAttack(p),WeaponRules.DAMAGE);}
        Vec3d ray=end.subtract(from);
        for(int i=1;i<=8;i++){Vec3d point=from.add(ray.multiply(i/8.0));world.spawnParticles(p,ParticleTypes.CRIT,false,false,point.x,point.y,point.z,1,0,0,0,0);}
        world.playSound(null,p.getX(),p.getY(),p.getZ(),SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST,SoundCategory.PLAYERS,0.45f,1.8f);
        p.sendMessage(Text.literal("АК-74  ·  "+(rounds-1)+" / "+WeaponRules.CAPACITY),true);
        return ActionResult.SUCCESS;
    }
    private int magazineSlot(ServerPlayerEntity p){for(int i=0;i<p.getInventory().size();i++)if(p.getInventory().getStack(i).isOf(MAGAZINE))return i;return -1;}
    private void reload(ServerPlayerEntity p,ItemStack stack){
        if(reloads.containsKey(p.getUuid())||ammo(stack)>=WeaponRules.CAPACITY)return;
        if(magazineSlot(p)<0){p.sendMessage(Text.literal("Нужен полный магазин 5,45 в инвентаре."),true);return;}
        reloads.put(p.getUuid(),new Reload(p.getInventory().getSelectedSlot(),serial(stack),tick+WeaponRules.RELOAD_TICKS));
        p.sendMessage(Text.literal("Перезарядка · 2,2 с. Не меняйте слот."),true);
    }
    @Override public void tick(MinecraftServer server){
        tick++;
        var iterator=reloads.entrySet().iterator();
        while(iterator.hasNext()){
            var e=iterator.next();var pending=e.getValue();ServerPlayerEntity p=server.getPlayerManager().getPlayer(e.getKey());
            if(p==null||!eligible(p)||!p.getMainHandStack().isOf(RIFLE)||!WeaponRules.sameWeapon(pending.serial,serial(p.getMainHandStack()),pending.slot,p.getInventory().getSelectedSlot())){iterator.remove();continue;}
            if(tick<pending.due)continue;
            iterator.remove();int slot=magazineSlot(p);
            if(slot<0){p.sendMessage(Text.literal("Перезарядка отменена: магазина больше нет."),true);continue;}
            // Both changes belong to the same vanilla player inventory; no money/DB mutation.
            p.getInventory().getStack(slot).decrement(1);setAmmo(p.getMainHandStack(),WeaponRules.CAPACITY);
            p.getInventory().markDirty();p.currentScreenHandler.sendContentUpdates();
            p.sendMessage(Text.literal("АК-74  ·  30 / 30"),true);
            p.getEntityWorld().playSound(null,p.getBlockPos(),SoundEvents.BLOCK_IRON_DOOR_CLOSE,SoundCategory.PLAYERS,0.5f,1.5f);
        }
    }
    @Override public void stopped(){reloads.clear();nextShot.clear();instance=null;}
    private static final class RifleItem extends Item {
        RifleItem(Settings settings){super(settings);}
        @Override public ActionResult use(World world,PlayerEntity player,Hand hand){
            if(world.isClient())return ActionResult.SUCCESS;
            return instance!=null&&player instanceof ServerPlayerEntity p?instance.use(p,hand):ActionResult.FAIL;
        }
    }
}
