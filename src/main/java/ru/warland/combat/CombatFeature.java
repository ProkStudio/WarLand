package ru.warland.combat;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
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
import ru.warland.enchantments.EnchantmentRules;
import ru.warland.ui.Menus;
import ru.warland.vehicles.VehicleFeature;
import ru.warland.vehicles.VehicleRules;

/** Five bounded arcade profiles, disposable magazines and server raycasts. */
public final class CombatFeature implements Feature {
    public static Item RIFLE,MAGAZINE;
    private static final Map<String,Item> GUNS=new LinkedHashMap<>();
    private static CombatFeature instance;
    private WarLandApi api;
    private long tick;
    private Map<String,WeaponProfile> profiles=WeaponProfile.defaults();
    private final VehicleFeature vehicles=new VehicleFeature();
    private final Map<UUID,Long> nextShot=new HashMap<>();
    private final Map<UUID,Reload> reloads=new HashMap<>();
    private record Reload(int slot,String serial,ItemStack exactStack,long due){}
    public CombatFeature(){}
    public static synchronized void registerItems(){
        if(RIFLE!=null)return;
        for(var profile:WeaponProfile.defaults().values()){
            var key=RegistryKey.of(RegistryKeys.ITEM,Identifier.of("warland",profile.id()));
            Item gun=Registry.register(Registries.ITEM,key,new GunItem(new Item.Settings().registryKey(key).maxCount(1)
                .component(DataComponentTypes.ITEM_MODEL,Identifier.of("warland","ak74"))
                .component(DataComponentTypes.CUSTOM_NAME,Text.literal(profile.name())),profile.id()));
            GUNS.put(profile.id(),gun);
        }
        RIFLE=GUNS.get("ak74");
        var key=RegistryKey.of(RegistryKeys.ITEM,Identifier.of("warland","magazine_545"));
        MAGAZINE=Registry.register(Registries.ITEM,key,new Item(new Item.Settings().registryKey(key).maxCount(16)));
    }
    @Override public void initialize(WarLandApi api){
        registerItems();this.api=api;instance=this;vehicles.initialize(api);
        // Optional complete profile array. Invalid/unknown entries fail startup; never trust client tuning.
        Path file=api.dataDirectory().resolve("combat-weapons.json");
        if(Files.exists(file))try{
            if(Files.size(file)>65536)throw new IllegalArgumentException("Weapon configuration too large");
            WeaponProfile[] loaded=new Gson().fromJson(Files.readString(file),WeaponProfile[].class);
            if(loaded==null||loaded.length!=GUNS.size())throw new IllegalArgumentException("Expected all five profiles");
            Map<String,WeaponProfile> next=new HashMap<>();
            for(var profile:loaded)if(profile==null||!GUNS.containsKey(profile.id())||next.put(profile.id(),profile)!=null)throw new IllegalArgumentException("Unknown or duplicate weapon profile");
            profiles=Map.copyOf(next);
        }catch(Exception invalid){throw new IllegalStateException("Invalid combat-weapons.json",invalid);}
        CommandRegistrationCallback.EVENT.register((d,r,e)->{
            d.register(CommandManager.literal("arsenal").executes(c->{menu(c.getSource().getPlayerOrThrow());return 1;})
                .then(CommandManager.literal("issue").requires(s->api.staff(s,"warland.combat.admin"))
                    .then(CommandManager.argument("profile",StringArgumentType.word()).suggests((c,b)->{GUNS.keySet().forEach(b::suggest);return b.buildFuture();})
                    .then(CommandManager.argument("reason",StringArgumentType.greedyString()).executes(c->{
                        var p=c.getSource().getPlayerOrThrow();String id=StringArgumentType.getString(c,"profile"),reason=StringArgumentType.getString(c,"reason");
                        if(eligible(p)&&GUNS.containsKey(id)&&VehicleRules.validReason(reason)){
                            p.getInventory().offerOrDrop(new ItemStack(GUNS.get(id)));api.audit(p.getUuid().toString(),"combat.issue",id+" "+reason);
                        }else api.reply(p,"Нужен допустимый профиль, причина 8–160 символов и свободный инвентарь выживания.");return 1;
                    })))));
            d.register(CommandManager.literal("armory").executes(c->{menu(c.getSource().getPlayerOrThrow());return 1;}));
            d.register(CommandManager.literal("gunfocus").requires(s->api.staff(s,"warland.combat.admin"))
                .then(CommandManager.argument("focus",StringArgumentType.word()).suggests((c,b)->{b.suggest("STEADY");b.suggest("QUICKLOAD");b.suggest("NONE");return b.buildFuture();})
                .then(CommandManager.argument("reason",StringArgumentType.greedyString()).executes(c->{
                    var p=c.getSource().getPlayerOrThrow();String value=StringArgumentType.getString(c,"focus"),reason=StringArgumentType.getString(c,"reason");
                    if(eligible(p)&&p.getMainHandStack().getItem() instanceof GunItem&&Set.of("NONE","STEADY","QUICKLOAD").contains(value)&&VehicleRules.validReason(reason)){
                        reloads.remove(p.getUuid());NbtComponent.set(DataComponentTypes.CUSTOM_DATA,p.getMainHandStack(),n->{n.putString("warland_focus",value);n.putInt("warland_focus_level",1);});
                        p.getInventory().markDirty();p.currentScreenHandler.sendContentUpdates();api.audit(p.getUuid().toString(),"combat.focus",value+" "+reason);
                    }else api.reply(p,"Держите оружие; укажите STEADY, QUICKLOAD или NONE и причину 8–160 символов.");return 1;
                }))));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{reloads.remove(h.player.getUuid());nextShot.remove(h.player.getUuid());});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->reloads.remove(entity.getUuid()));
    }
    @Override public void serverStarted(MinecraftServer server){tick=0;vehicles.serverStarted(server);}
    private void menu(ServerPlayerEntity p){
        var entries=new ArrayList<WarLandApi.MenuEntry>();
        for(var profile:profiles.values())entries.add(new WarLandApi.MenuEntry(Menus.icon(GUNS.get(profile.id()),profile.name(),"ПКМ — огонь; Shift + ПКМ — перезарядка",profile.capacity()+" патронов · "+profile.range()+" блоков","Прототипы используют общую модель"),()->api.reply(p,profile.name()+": новый ствол пуст. Каждый полный магазин заряжает ёмкость этого профиля; старый остаток теряется. АК-74 доступен через верстак, остальные пока только тестовая выдача персоналом.")));
        entries.add(new WarLandApi.MenuEntry(Menus.icon(MAGAZINE,"Полный магазин","Не меняйте слот во время перезарядки"),()->api.reply(p,"Смена предмета, смерть и выход отменяют перезарядку без списания магазина. Полное выпадение инвентаря не изменено.")));
        api.menu(p,"Арсенал · 5 профилей",entries);
    }
    private boolean eligible(ServerPlayerEntity p){return api.ready()&&p.isAlive()&&!p.isSpectator()&&!p.isCreative()&&!p.hasVehicle()&&!api.inventoryLocked(p.getUuid());}
    private static String serial(ItemStack stack){return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getString("warland_gun","");}
    private static int ammo(ItemStack stack,WeaponProfile profile){return profile.ammo(stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt().getInt("warland_ammo",0));}
    private static void setAmmo(ItemStack stack,int value,WeaponProfile profile){NbtComponent.set(DataComponentTypes.CUSTOM_DATA,stack,n->n.putInt("warland_ammo",profile.ammo(value)));}
    private ActionResult use(ServerPlayerEntity p,Hand hand,String id){
        if(hand!=Hand.MAIN_HAND||!eligible(p))return ActionResult.FAIL;
        ItemStack stack=p.getStackInHand(hand);WeaponProfile profile=profiles.get(id);
        if(serial(stack).isEmpty())NbtComponent.set(DataComponentTypes.CUSTOM_DATA,stack,n->n.putString("warland_gun",UUID.randomUUID().toString()));
        var nbt=stack.getOrDefault(DataComponentTypes.CUSTOM_DATA,NbtComponent.DEFAULT).copyNbt();
        var focus=EnchantmentRules.parse(nbt.getString("warland_focus","NONE"));int level=nbt.getInt("warland_focus_level",0);
        if(p.isSneaking()){reload(p,stack,profile,EnchantmentRules.finalReload(profile.reloadTicks(),focus,level));return ActionResult.SUCCESS;}
        int rounds=ammo(stack,profile);
        if(!WeaponRules.mayFire(tick,nextShot.getOrDefault(p.getUuid(),0L),rounds,reloads.containsKey(p.getUuid()))){if(rounds==0)p.sendMessage(Text.literal("Магазин пуст. Shift + ПКМ — перезарядка."),true);return ActionResult.FAIL;}
        nextShot.put(p.getUuid(),tick+profile.shotTicks());setAmmo(stack,rounds-1,profile);
        p.getItemCooldownManager().set(stack,profile.shotTicks());p.getInventory().markDirty();p.currentScreenHandler.sendContentUpdates();
        if(api instanceof CoreRuntime core)core.tag(p.getUuid());
        ServerWorld world=p.getEntityWorld();Vec3d from=p.getEyePos(),aim=p.getRotationVec(1);
        Map<Entity,Float> damage=new HashMap<>();double spread=EnchantmentRules.finalSpread(profile.spread(),focus,level);
        for(int pellet=0;pellet<profile.pellets();pellet++){
            Vec3d direction=aim.add((world.random.nextDouble()*2-1)*spread,(world.random.nextDouble()*2-1)*spread,(world.random.nextDouble()*2-1)*spread).normalize();
            Vec3d end=from.add(direction.multiply(profile.range()));
            var wall=world.raycast(new RaycastContext(from,end,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,p));
            if(wall.getType()!=HitResult.Type.MISS)end=wall.getPos();
            var hit=ProjectileUtil.raycast(p,from,end,p.getBoundingBox().stretch(direction.multiply(profile.range())).expand(1),entity->entity!=p&&entity.isAlive()&&!entity.isSpectator()&&entity.canHit(),from.squaredDistanceTo(end));
            if(hit!=null){end=hit.getPos();if(api.canDamage(p,hit.getEntity()))damage.merge(hit.getEntity(),profile.damageAt(from.distanceTo(end)),Float::sum);}
            Vec3d ray=end.subtract(from);int samples=profile.pellets()==1?6:2;
            for(int i=1;i<=samples;i++){Vec3d point=from.add(ray.multiply(i/(double)samples));world.spawnParticles(p,ParticleTypes.CRIT,false,false,point.x,point.y,point.z,1,0,0,0,0);}
        }
        damage.forEach((target,value)->{if(value>0&&!vehicles.damage(p,target,(int)Math.ceil(value)))target.damage(world,world.getDamageSources().playerAttack(p),value);});
        world.playSound(null,p.getX(),p.getY(),p.getZ(),SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST,SoundCategory.PLAYERS,.45f,1.8f);
        p.sendMessage(Text.literal(profile.name()+" · "+(rounds-1)+" / "+profile.capacity()),true);return ActionResult.SUCCESS;
    }
    private int magazineSlot(ServerPlayerEntity p){for(int i=0;i<p.getInventory().size();i++)if(p.getInventory().getStack(i).isOf(MAGAZINE))return i;return -1;}
    private void reload(ServerPlayerEntity p,ItemStack stack,WeaponProfile profile,int duration){
        if(reloads.containsKey(p.getUuid())||ammo(stack,profile)>=profile.capacity())return;
        if(magazineSlot(p)<0){p.sendMessage(Text.literal("Нужен полный магазин в инвентаре."),true);return;}
        reloads.put(p.getUuid(),new Reload(p.getInventory().getSelectedSlot(),serial(stack),stack,tick+duration));
        p.sendMessage(Text.literal("Перезарядка · "+duration/20.0+" с. Не меняйте слот."),true);
    }
    @Override public void tick(MinecraftServer server){
        tick++;vehicles.tick(server);
        var iterator=reloads.entrySet().iterator();
        while(iterator.hasNext()){
            var e=iterator.next();var pending=e.getValue();var p=server.getPlayerManager().getPlayer(e.getKey());
            if(p==null||!eligible(p)||p.getMainHandStack()!=pending.exactStack||!(p.getMainHandStack().getItem() instanceof GunItem)||!WeaponRules.sameWeapon(pending.serial,serial(p.getMainHandStack()),pending.slot,p.getInventory().getSelectedSlot())){iterator.remove();continue;}
            if(tick<pending.due)continue;iterator.remove();int slot=magazineSlot(p);
            if(slot<0){p.sendMessage(Text.literal("Перезарядка отменена: магазина больше нет."),true);continue;}
            WeaponProfile profile=profiles.get(((GunItem)p.getMainHandStack().getItem()).id);
            // Both mutations are in one vanilla player inventory save; no DB/item cross-store transaction.
            p.getInventory().getStack(slot).decrement(1);setAmmo(p.getMainHandStack(),profile.capacity(),profile);
            p.getInventory().markDirty();p.currentScreenHandler.sendContentUpdates();
            p.sendMessage(Text.literal(profile.name()+" · "+profile.capacity()+" / "+profile.capacity()),true);
        }
    }
    @Override public void stopped(){vehicles.stopped();reloads.clear();nextShot.clear();instance=null;}
    private static final class GunItem extends Item {
        final String id;GunItem(Settings settings,String id){super(settings);this.id=id;}
        @Override public ActionResult use(World world,PlayerEntity player,Hand hand){if(world.isClient())return ActionResult.SUCCESS;return instance!=null&&player instanceof ServerPlayerEntity p?instance.use(p,hand,id):ActionResult.FAIL;}
    }
}
