package ru.warland.vehicles;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.*;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import ru.warland.api.*;
import ru.warland.core.CoreRuntime;
import ru.warland.vehicles.VehicleState.*;

/** Ground courier prototype. Vanilla seat, server-only motion, no world edits or new assets. */
public final class VehicleFeature implements Feature {
    private static final String TAG="warland.vehicle.seat.v1";
    private WarLandApi api;
    private VehicleStore store;
    private long tick;
    private final Map<UUID,Active> active=new HashMap<>();
    private final Map<UUID,Long> cooldown=new HashMap<>();
    private static final class Active {
        final UUID id;final ArmorStandEntity seat;Vec3d position;
        double speed,yaw;int throttle,steering,lease;long inputUntil;boolean pending;
        Active(UUID id,ArmorStandEntity seat,double yaw){this.id=id;this.seat=seat;this.position=seat.getEntityPos();this.yaw=yaw;}
    }
    @Override public void initialize(WarLandApi api){
        this.api=api;
        CommandRegistrationCallback.EVENT.register((d,r,e)->d.register(CommandManager.literal("vehicle")
            .executes(c->player(c.getSource(),this::list))
            .then(CommandManager.literal("deploy").then(CommandManager.argument("id",StringArgumentType.word()).executes(c->player(c.getSource(),p->deploy(p,id(c.getSource(),StringArgumentType.getString(c,"id")))))))
            .then(CommandManager.literal("mount").then(CommandManager.argument("id",StringArgumentType.word()).executes(c->player(c.getSource(),p->mount(p,id(c.getSource(),StringArgumentType.getString(c,"id")))))))
            .then(CommandManager.literal("park").executes(c->player(c.getSource(),this::parkOwned)))
            .then(CommandManager.literal("drive").then(CommandManager.argument("throttle",IntegerArgumentType.integer(-1,1)).then(CommandManager.argument("steering",IntegerArgumentType.integer(-1,1)).executes(c->player(c.getSource(),p->drive(p,IntegerArgumentType.getInteger(c,"throttle"),IntegerArgumentType.getInteger(c,"steering")))))))
            .then(CommandManager.literal("grant").requires(s->api.staff(s,"warland.vehicle.admin")).then(CommandManager.argument("player",EntityArgumentType.player()).then(CommandManager.argument("reason",StringArgumentType.greedyString()).executes(c->{grant(c.getSource(),EntityArgumentType.getPlayer(c,"player"),StringArgumentType.getString(c,"reason"));return 1;}))))
            .then(CommandManager.literal("recover").requires(s->api.staff(s,"warland.vehicle.admin")).then(CommandManager.argument("id",StringArgumentType.word()).then(CommandManager.argument("reason",StringArgumentType.greedyString()).executes(c->{recover(c.getSource(),id(c.getSource(),StringArgumentType.getString(c,"id")),StringArgumentType.getString(c,"reason"));return 1;}))))));
        // A saved seat can never recreate ownership. Unknown/old projections are removed on load.
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{
            if(entity.getCommandTags().contains(TAG)&&active.values().stream().noneMatch(a->a.seat==entity))entity.discard();
        });
        UseEntityCallback.EVENT.register((p,w,h,entity,hit)->entity.getCommandTags().contains(TAG)?ActionResult.FAIL:ActionResult.PASS);
        AttackEntityCallback.EVENT.register((p,w,h,entity,hit)->{
            if(!entity.getCommandTags().contains(TAG))return ActionResult.PASS;
            if(p instanceof ServerPlayerEntity sp&&h==Hand.MAIN_HAND)damage(sp,entity,5);
            return ActionResult.FAIL;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{
            cooldown.remove(h.player.getUuid());
            for(var a:new ArrayList<>(active.values()))if(owner(a).equals(h.player.getUuid())){a.throttle=0;a.steering=0;a.inputUntil=0;a.seat.removeAllPassengers();}
        });
    }
    @Override public void serverStarted(MinecraftServer server){
        tick=0;store=new VehicleStore(()->api.getState(VehicleStore.NAMESPACE,VehicleStore.KEY),json->api.setState(VehicleStore.NAMESPACE,VehicleStore.KEY,json),server::execute);store.load();
    }
    private boolean ready(){return api.ready()&&store!=null&&store.ready();}
    private UUID owner(Active a){return store.snapshot().vehicles().get(a.id).owner();}
    private boolean valid(ServerPlayerEntity p){return ready()&&p.isAlive()&&!p.isCreative()&&!p.isSpectator()&&!api.inventoryLocked(p.getUuid());}
    private boolean rate(ServerPlayerEntity p){if(tick<cooldown.getOrDefault(p.getUuid(),0L))return false;cooldown.put(p.getUuid(),tick+5);return true;}
    private void list(ServerPlayerEntity p){
        if(!ready()){api.reply(p,"Ангар недоступен: загрузка или ошибка хранилища. Техника не создаётся.");return;}
        api.reply(p,"Курьер: /vehicle deploy <ID>, mount <ID>, drive <-1..1> <-1..1>, park. Газ действует 5 секунд; 0 0 — тормоз. Только ровная пустошь. Покупки/заправка пока закрыты.");
        for(var v:store.snapshot().vehicles().values())if(v.owner().equals(p.getUuid()))api.reply(p,v.id()+" · "+statusLabel(v.status())+" · прочность "+v.health()+" · топливо "+v.fuel());
    }
    private static String statusLabel(Status s){return switch(s){case OWNED->"в ангаре";case DEPLOYED->"развёрнут";case PARKED->"припаркован";case RECOVERY->"сверка после прерывания";case DESTROYED->"уничтожен навсегда";};}
    private void grant(ServerCommandSource source,ServerPlayerEntity target,String reason){
        if(!ready()||!VehicleRules.validReason(reason)){source.sendError(Text.literal("Ангар не готов либо нужна причина 8–160 символов."));return;}
        var fleet=store.snapshot();
        if(fleet.owned(target.getUuid())>=VehicleRules.MAX_OWNED||fleet.vehicles().size()>=VehicleRules.MAX_RECORDS){source.sendError(Text.literal("Достигнут лимит техники."));return;}
        var v=new Vehicle(UUID.randomUUID(),target.getUuid(),Status.OWNED,VehicleRules.MAX_FUEL,100,target.getEntityWorld().getRegistryKey().getValue().toString(),target.getX(),target.getY(),target.getZ(),0);
        change(f->f.put(v),()->{api.audit(source.getName(),"vehicle.grant",v.id()+" "+reason);api.reply(target,"Курьер выдан: "+v.id()+". Не покупка; тестовая выдача персоналом.");},target);
    }
    private Vehicle owned(ServerPlayerEntity p,UUID id){
        if(!valid(p)||id==null||!rate(p))return null;
        var v=store.snapshot().vehicles().get(id);
        if(v==null||!v.owner().equals(p.getUuid())){api.reply(p,"Техника не найдена либо у вас нет ключа владельца.");return null;}return v;
    }
    private void deploy(ServerPlayerEntity p,UUID id){
        Vehicle v=owned(p,id);if(v==null)return;
        if(p.hasVehicle()||api.inCombat(p.getUuid())||active.size()>=VehicleRules.MAX_ACTIVE||active.values().stream().anyMatch(a->owner(a).equals(p.getUuid()))||(v.status()!=Status.OWNED&&v.status()!=Status.PARKED)){api.reply(p,"Развёртывание запрещено: состояние, бой или лимит активной техники.");return;}
        ServerWorld world=p.getEntityWorld();Vec3d point=v.status()==Status.OWNED?p.getEntityPos().add(2,0,0):new Vec3d(v.x(),v.y(),v.z());
        if(v.status()==Status.PARKED&&(!v.world().equals(world.getRegistryKey().getValue().toString())||p.getEntityPos().squaredDistanceTo(point)>64)){api.reply(p,"Вернитесь к месту парковки (не дальше 8 блоков).");return;}
        if(!space(p,world,point)||occupied(world,point,null)){api.reply(p,"Нужна свободная ровная площадка в загруженной пустоши.");return;}
        Vehicle deployed=v.at(Status.DEPLOYED,v.fuel(),v.health(),world.getRegistryKey().getValue().toString(),point.x,point.y,point.z,v.yaw());
        change(f->f.put(deployed),()->{
            if(!valid(p)||p.getEntityWorld()!=world||p.getEntityPos().squaredDistanceTo(point)>64||api.inCombat(p.getUuid())||!space(p,world,point)||occupied(world,point,null)){
                // Durable but no entity: quarantine instead of silently creating a second projection.
                store.change(f->f.put(deployed.status(Status.RECOVERY)),()->{},()->{});return;
            }
            ArmorStandEntity seat=new ArmorStandEntity(world,point.x,point.y,point.z);
            seat.setInvulnerable(true);seat.setNoGravity(true);seat.setInvisible(true);seat.setCustomName(Text.literal("Курьер · "+p.getName().getString()));seat.setCustomNameVisible(true);
            seat.equipStack(EquipmentSlot.HEAD,new ItemStack(Items.IRON_BLOCK));seat.addCommandTag(TAG);
            Active a=new Active(id,seat,v.yaw());active.put(id,a);
            if(!world.spawnEntity(seat)){active.remove(id);store.change(f->f.put(deployed.status(Status.RECOVERY)),()->{},()->{});return;}
            api.audit(p.getUuid().toString(),"vehicle.deploy",id.toString());api.reply(p,"Курьер развёрнут. /vehicle mount "+id);
        },p);
    }
    private void mount(ServerPlayerEntity p,UUID id){
        Vehicle v=owned(p,id);Active a=active.get(id);if(v==null||a==null)return;
        if(p.hasVehicle()||a.pending||p.getEntityWorld()!=a.seat.getEntityWorld()||p.getEntityPos().squaredDistanceTo(a.position)>16||!a.seat.getPassengerList().isEmpty())return;
        p.startRiding(a.seat,true,true);
    }
    private void drive(ServerPlayerEntity p,int throttle,int steering){
        if(!valid(p)||!rate(p))return;
        for(var a:active.values())if(owner(a).equals(p.getUuid())&&p.getVehicle()==a.seat){a.throttle=throttle;a.steering=steering;a.inputUntil=tick+100;return;}
        api.reply(p,"Сначала сядьте в свой Курьер.");
    }
    private void parkOwned(ServerPlayerEntity p){
        if(!valid(p)||!rate(p))return;
        for(var a:active.values())if(owner(a).equals(p.getUuid())){
            if(p.getEntityWorld()!=a.seat.getEntityWorld()||p.getEntityPos().squaredDistanceTo(a.position)>64||api.inCombat(p.getUuid())){api.reply(p,"Парковка недоступна в бою или вдали от техники.");return;}
            park(a,Status.PARKED);return;
        }
    }
    private void park(Active a,Status status){
        if(a.pending||!store.idle())return;
        var v=store.snapshot().vehicles().get(a.id);a.pending=true;a.speed=0;
        Vehicle next=v.at(status,v.fuel(),v.health(),v.world(),a.position.x,a.position.y,a.position.z,a.yaw);
        if(!store.change(f->f.put(next),()->remove(a),()->remove(a)))a.pending=false;
    }
    private void recover(ServerCommandSource source,UUID id,String reason){
        if(!ready()||id==null||!VehicleRules.validReason(reason))return;
        Vehicle v=store.snapshot().vehicles().get(id);
        if(v==null||v.status()!=Status.RECOVERY){source.sendError(Text.literal("Восстанавливать можно только технику на сверке. Уничтоженная не возвращается."));return;}
        if(!store.change(f->f.put(v.status(Status.PARKED)),()->{api.audit(source.getName(),"vehicle.recover",id+" "+reason);source.sendFeedback(()->Text.literal("Парковка восстановлена в последней сохранённой точке; топливо и прочность не увеличены."),false);},()->source.sendError(Text.literal("Хранилище заблокировано."))))source.sendError(Text.literal("Хранилище занято."));
    }
    /** Called by the rifle BEFORE vanilla damage; vanilla hits cannot move/equip the seat. */
    public boolean damage(ServerPlayerEntity attacker,Entity target,int amount){
        if(!target.getCommandTags().contains(TAG))return false;
        if(!valid(attacker)||!rate(attacker)||!api.canDamage(attacker,target)||!(api instanceof CoreRuntime core)||core.protectedAt(attacker.getEntityWorld(),attacker.getBlockPos())||core.protectedAt((ServerWorld)target.getEntityWorld(),target.getBlockPos()))return true;
        for(var a:active.values())if(a.seat==target&&!a.pending&&store.idle()){
            Vehicle v=store.snapshot().vehicles().get(a.id);Vehicle next=v.damage(Math.max(1,Math.min(100,amount)));a.pending=true;a.speed=0;
            if(!store.change(f->f.put(next),()->{
                a.pending=false;core.tag(attacker.getUuid());core.tag(v.owner());
                api.audit(attacker.getUuid().toString(),"vehicle.damage",a.id+" health="+next.health());
                if(next.status()==Status.DESTROYED)remove(a);
            },()->remove(a)))a.pending=false;
            break;
        }return true;
    }
    @Override public void tick(MinecraftServer server){
        tick++;if(!ready()){for(var a:new ArrayList<>(active.values()))remove(a);return;}if(tick%2!=0)return;
        for(var a:new ArrayList<>(active.values())){
            if(a.pending)continue;
            ServerPlayerEntity p=server.getPlayerManager().getPlayer(owner(a));
            if(a.seat.isRemoved()){park(a,Status.RECOVERY);continue;}
            // Reassert absolute authoritative position, never accept a client vehicle delta.
            a.seat.setVelocity(Vec3d.ZERO);a.seat.setPosition(a.position);a.seat.setYaw((float)a.yaw);
            for(var passenger:new ArrayList<>(a.seat.getPassengerList()))if(passenger!=p)passenger.stopRiding();
            if(p==null||!valid(p)||p.getEntityWorld()!=a.seat.getEntityWorld()||p.getVehicle()!=a.seat){
                a.speed=0;if(p==null||!api.inCombat(p.getUuid()))park(a,Status.PARKED);continue;
            }
            if(tick>=a.inputUntil){a.throttle=0;a.steering=0;}
            a.speed=VehicleRules.speed(a.speed,a.throttle);a.yaw=VehicleRules.heading(a.yaw,a.steering);
            if(Math.abs(a.speed)<.001)continue;
            if(a.lease<=0){reserve(a);continue;}
            a.lease=Math.max(0,a.lease-2);
            double radians=Math.toRadians(a.yaw);Vec3d next=a.position.add(-Math.sin(radians)*a.speed*2,0,Math.cos(radians)*a.speed*2);
            if(!space(p,(ServerWorld)a.seat.getEntityWorld(),next)||occupied((ServerWorld)a.seat.getEntityWorld(),next,a)){a.speed=0;a.throttle=0;continue;}
            a.position=next;a.seat.setPosition(next);
        }
    }
    private void reserve(Active a){
        if(!store.idle())return;Vehicle v=store.snapshot().vehicles().get(a.id);int lease=VehicleRules.reserveFuel(v.fuel());
        if(lease==0){a.speed=0;a.throttle=0;return;}a.pending=true;
        Vehicle next=v.at(Status.DEPLOYED,v.fuel()-lease,v.health(),v.world(),a.position.x,a.position.y,a.position.z,a.yaw);
        if(!store.change(f->f.put(next),()->{a.pending=false;a.lease=lease;},()->remove(a)))a.pending=false;
    }
    private boolean space(ServerPlayerEntity p,ServerWorld world,Vec3d point){
        if(!(api instanceof CoreRuntime core))return false; // explicit conservative adapter until transport ACL exists
        Box box=new Box(point.x-.7,point.y,point.z-.7,point.x+.7,point.y+3.5,point.z+.7);
        for(BlockPos pos:BlockPos.iterate(BlockPos.ofFloored(box.minX,box.minY-1,box.minZ),BlockPos.ofFloored(box.maxX,box.maxY,box.maxZ))){
            if(!world.isChunkLoaded(pos)||!world.getWorldBorder().contains(pos)||core.protectedAt(world,pos)||!api.canBuild(p,world,pos)||!world.getFluidState(pos).isEmpty())return false;
            if(pos.getY()==Math.floor(point.y)-1&&!world.getBlockState(pos).isSolidBlock(world,pos))return false;
        }
        return world.isSpaceEmpty(box);
    }
    private boolean occupied(ServerWorld world,Vec3d point,Active self){
        Box box=new Box(point.x-.7,point.y,point.z-.7,point.x+.7,point.y+3.5,point.z+.7);
        return !world.getOtherEntities(self==null?null:self.seat,box,e->!(self!=null&&self.seat.hasPassenger(e))&&!e.isSpectator()).isEmpty();
    }
    private void remove(Active a){a.seat.removeAllPassengers();a.seat.discard();active.remove(a.id);}
    private void change(java.util.function.UnaryOperator<Fleet> edit,Runnable after,ServerPlayerEntity p){if(!store.change(edit,after,()->api.reply(p,"Ошибка сохранения. Ангар закрыт; повторная выдача запрещена.")))api.reply(p,"Ангар занят сохранением. Повторите позже.");}
    @Override public void stopped(){if(store!=null)store.close();for(var a:new ArrayList<>(active.values()))remove(a);cooldown.clear();}
    private static int player(ServerCommandSource s,Consumer<ServerPlayerEntity> action){var p=s.getPlayer();if(p==null){s.sendError(Text.literal("Эта команда доступна игроку."));return 0;}action.accept(p);return 1;}
    private static UUID id(ServerCommandSource source,String text){try{return UUID.fromString(text);}catch(IllegalArgumentException bad){source.sendError(Text.literal("Неверный ID техники."));return null;}}
}
