package ru.warland.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import ru.warland.WarLand;
import ru.warland.api.*;
import ru.warland.auth.AuthRuntime;
import ru.warland.data.Store;
import ru.warland.nations.NationsService;
import ru.warland.war.WarService;
import ru.warland.ui.Menus;

public final class CoreRuntime implements WarLandApi {
 public static CoreRuntime INSTANCE;
 public record Warp(ServerWorld world,BlockPos feet){}
 public record SafeZone(ServerWorld world,BlockPos feet,int radius){}
 private record ProfileJoin(long tutorial,boolean hubPending){}
 public final GameConfig config;public final Store store;public final NationsService nations;public final WarService wars;public final AuthRuntime auth;
 private MinecraftServer server;private volatile boolean ready,stopping;
 public final Map<String,Warp> warps=new HashMap<>();public final List<SafeZone> safeZones=new ArrayList<>();
 private final Map<UUID,Long> combat=new HashMap<>(),rateLimits=new HashMap<>();
 private final Set<UUID> arrivals=new HashSet<>();
 public final Map<UUID,PendingTeleport> teleports=new HashMap<>();
 private final List<Feature> features=new ArrayList<>();public final List<String> modules=new ArrayList<>();
 private long tick;private final Path dir;
 public record PendingTeleport(String id,Warp target,long due,BlockPos origin){}
 public CoreRuntime()throws Exception{
  INSTANCE=this;Path game=FabricLoader.getInstance().getGameDir();dir=game.resolve("warland");
  config=GameConfig.load(FabricLoader.getInstance().getConfigDir().resolve("warland/core.json"));
  store=new Store(dir.resolve("warland.db"));nations=new NationsService(store,config,this::actionLease);wars=new WarService(store,config,nations);auth=new AuthRuntime(this);
 }
 public void initialize(){
  auth.initialize();Protection.register(this);CoreCommands.register(this);
  // Content's read-only terminals use an explicit phase before the default protection phase.
  load("ru.warland.combat.CombatFeature",false);load("ru.warland.content.ContentFeature",false);load("ru.warland.economy.MarketFeature",true);load("ru.warland.season.SeasonFeature",false);
  ServerLifecycleEvents.SERVER_STARTED.register(this::start);
  ServerTickEvents.END_SERVER_TICK.register(this::tick);
  ServerLifecycleEvents.SERVER_STOPPING.register(s->{stopping=true;ready=false;auth.close();for(Feature f:features)try{f.stopped();}catch(Exception e){WarLand.LOG.error("Feature stop failed",e);}store.close();});
  ServerPlayConnectionEvents.JOIN.register((h,sender,s)->onJoin(h.player));
  ServerPlayConnectionEvents.DISCONNECT.register((h,s)->{UUID id=h.player.getUuid();teleports.remove(id);arrivals.remove(id);rateLimits.remove(id);combat.remove(id);});
 }
 private void load(String name,boolean withStore){try{Class<?> type=Class.forName(name);Feature f=(Feature)(withStore?type.getConstructor(Store.class,GameConfig.class).newInstance(store,config):type.getConstructor().newInstance());f.initialize(this);features.add(f);modules.add(type.getSimpleName());}catch(ClassNotFoundException e){WarLand.LOG.warn("Optional module not included: {}",name);}catch(Exception e){throw new IllegalStateException("Module initialization failed: "+name,e);}}
 private void start(MinecraftServer s){server=s;
  boolean local=Set.of("127.0.0.1","::1","localhost").contains(s.getServerIp());
  if(config.requireOnlineModeForPublic&&!s.isOnlineMode()&&!(local&&Boolean.getBoolean("warland.allowOfflineDevelopment"))){WarLand.LOG.error("WarLand LOCKED: offline-mode public server is not safe. No economic or administration commands are enabled.");return;}
  store.start().thenCompose(v->auth.start()).thenCompose(v->nations.refresh()).thenCompose(v->wars.initialize()).whenComplete((v,e)->s.execute(()->{
   if(stopping)return;
   if(e!=null){WarLand.LOG.error("WarLand database startup failed; interactions locked",e);return;}
   ready=true;for(Feature f:features)f.serverStarted(s);WarLand.LOG.info("WarLand ready: SQLite WAL, modules {}",modules);
   for(ServerPlayerEntity p:s.getPlayerManager().getPlayerList())onJoin(p);
  }));
 }
 private void onJoin(ServerPlayerEntity p){
  if(!ready||!auth.attach(p)){p.networkHandler.disconnect(Text.literal("WarLand: требуется защищённая авторизация до входа в мир."));return;}
  UUID id=p.getUuid();long now=System.currentTimeMillis();
  store.tx(c->{
   if(!auth.authenticated(p))throw new IllegalStateException("Admission expired before profile transaction");
   boolean fresh=Store.scalar(c,"SELECT COUNT(*) FROM profiles WHERE uuid=?",id.toString())==0;
   Store.update(c,"INSERT INTO profiles(uuid,name,joined,last_seen) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen",id.toString(),p.getName().getString(),now,now);
   if(fresh)Store.update(c,"INSERT INTO state(namespace,key,json) VALUES('onboarding',?,'pending') ON CONFLICT(namespace,key) DO NOTHING",id.toString());
   if(!Store.grantStarter(c,id,config.startingBalance))throw new IllegalStateException("Starter balance overflow");
   return new ProfileJoin(Store.scalar(c,"SELECT tutorial FROM profiles WHERE uuid=?",id.toString()),"pending".equals(Store.string(c,"SELECT json FROM state WHERE namespace='onboarding' AND key=?",id.toString())));
  }).whenComplete((profile,e)->server.execute(()->{if(!auth.authenticated(p))return;if(e!=null){p.networkHandler.disconnect(Text.literal("Не удалось безопасно загрузить профиль."));WarLand.LOG.error("Profile initialization failed",e);return;}
   auth.profileReady(p);
   if(profile.hubPending())arrivals.add(id);
   reply(p,"Добро пожаловать в WarLand! /warland — главное меню. /balance — баланс.");if(profile.tutorial()==0)reply(p,"Начните с /warland tutorial — без обучения нельзя основать государство.");
  }));
 }
 private boolean clearArrival(Warp warp){BlockPos feet=warp.feet;ServerWorld world=warp.world;return world.getWorldBorder().contains(feet)&&world.getBlockState(feet).isAir()&&world.getBlockState(feet.up()).isAir()&&world.getBlockState(feet.down()).isSolidBlock(world,feet.down())&&world.getFluidState(feet.down()).isEmpty();}
 private void firstArrivals(MinecraftServer s){
  Warp spawn=warps.get("spawn");if(spawn==null||!safe(spawn.world,spawn.feet)||!clearArrival(spawn))return;
  var iterator=arrivals.iterator();while(iterator.hasNext()){
   UUID id=iterator.next();ServerPlayerEntity p=s.getPlayerManager().getPlayer(id);
   if(p==null||!authorized(p)){iterator.remove();continue;}
   if(!p.isAlive()||p.hasVehicle()||inCombat(id)||inventoryLocked(id))continue;
   BlockPos feet=spawn.feet;p.teleport(spawn.world,feet.getX()+0.5,feet.getY(),feet.getZ()+0.5,Set.of(),p.getYaw(),p.getPitch(),true);
   if(p.getEntityWorld()!=spawn.world||!p.getBlockPos().equals(feet))continue;
   iterator.remove();teleports.remove(id);
   store.state("onboarding",id.toString(),"done").whenComplete((v,e)->{if(e!=null)s.execute(()->{WarLand.LOG.error("Hub arrival checkpoint failed",e);if(online(p))p.networkHandler.disconnect(Text.literal("Не удалось сохранить прибытие в хаб. Повторите вход."));});});
   reply(p,"Вы в безопасной столице. /warland tutorial — обучение; /quests — задания.");
  }
 }
 private void tick(MinecraftServer s){if(!ready)return;tick++;long now=System.currentTimeMillis();
  for(Feature f:features)f.tick(s);
  if(tick%20!=0)return;wars.tick(s);
  combat.entrySet().removeIf(e->e.getValue()<now);
  firstArrivals(s);
  var iterator=teleports.entrySet().iterator();while(iterator.hasNext()){
   var e=iterator.next();ServerPlayerEntity p=s.getPlayerManager().getPlayer(e.getKey());PendingTeleport t=e.getValue();
   if(p==null||!authorized(p)||!p.isAlive()||p.hasVehicle()||inCombat(e.getKey())||inventoryLocked(e.getKey())||!canWarp(p,t.id)||!Objects.equals(warps.get(t.id),t.target)||p.getBlockPos().getSquaredDistance(t.origin)>0.5){if(p!=null)reply(p,"Перемещение отменено.");iterator.remove();continue;}
   if(now>=t.due){iterator.remove();var feet=t.target.feet;var w=t.target.world;
    if(clearArrival(t.target)){
     p.teleport(w,feet.getX()+0.5,feet.getY(),feet.getZ()+0.5,Set.of(),p.getYaw(),p.getPitch(),true);reply(p,"Вы прибыли.");
    }else reply(p,"Точка прибытия небезопасна. Телепортация отменена.");
   }
  }
  if(tick%1200==0){for(ServerPlayerEntity p:s.getPlayerManager().getPlayerList())if(authorized(p))store.tx(c->{if(auth.authenticated(p))Store.update(c,"UPDATE profiles SET last_seen=?,active_seconds=active_seconds+60 WHERE uuid=?",now,p.getUuid().toString());return null;});nations.applyTaxes().exceptionally(e->{WarLand.LOG.error("Tax tick failed",e);return null;});}
  if(tick%12000==0)store.checkpoint().exceptionally(e->{WarLand.LOG.error("Checkpoint failed",e);return null;});
 }
 public boolean authorized(ServerPlayerEntity p){return ready&&!stopping&&auth.allowed(p);}
 /** Called only when a user action enters a service, never later from the DB worker. */
 private BooleanSupplier actionLease(UUID actor){
  if(server==null||!server.isOnThread())return ()->false;
  ServerPlayerEntity player=server.getPlayerManager().getPlayer(actor);
  return player!=null&&online(player)?auth.lease(player):()->false;
 }
 public boolean online(ServerPlayerEntity p){return authorized(p)&&server!=null&&server.getPlayerManager().getPlayer(p.getUuid())==p;}
 public boolean gate(ServerPlayerEntity p){if(!authorized(p)){reply(p,"Системы временно недоступны или авторизация истекла.");return false;}long now=System.currentTimeMillis(),last=rateLimits.getOrDefault(p.getUuid(),0L);if(now-last<250){reply(p,"Не так быстро.");return false;}rateLimits.put(p.getUuid(),now);return true;}
 public void tag(UUID id){combat.put(id,System.currentTimeMillis()+config.combatSeconds*1000L);teleports.remove(id);}
 public void later(ServerPlayerEntity p,CompletableFuture<String> f){f.whenComplete((v,e)->server.execute(()->{if(!online(p))return;if(e==null)reply(p,v);else{Throwable root=e;while(root.getCause()!=null)root=root.getCause();reply(p,"Не выполнено: "+Objects.toString(root.getMessage(),"ошибка операции"));WarLand.LOG.debug("User operation rejected",e);}}));}
 public void confirm(ServerPlayerEntity p,String title,String detail,Runnable action){menu(p,title,List.of(new MenuEntry(Menus.icon(net.minecraft.item.Items.LIME_CONCRETE,"Подтвердить",detail,"Действие будет записано в журнал"),action),new MenuEntry(Menus.icon(net.minecraft.item.Items.RED_CONCRETE,"Отмена"),()->reply(p,"Отменено."))));}
 public boolean canWarp(ServerPlayerEntity p,String id){Warp warp=warps.get(id);if(!authorized(p)||warp==null||warp.world.getRegistryKey()==net.minecraft.world.World.END)return false;var member=nations.member(p.getUuid());String claim=nations.claim(warp.world.getRegistryKey().getValue().toString(),warp.feet.getX()>>4,warp.feet.getZ()>>4);return CoreSafety.warpAllowed(id,member==null?null:member.nation(),claim);}
 public void teleport(ServerPlayerEntity p,String id){Warp w=warps.get(id);if(w==null){reply(p,"Точка пока недоступна.");return;}if(!canWarp(p,id)){reply(p,"Эта точка недоступна вашему государству.");return;}if(inventoryLocked(p.getUuid())||inCombat(p.getUuid())||p.hasVehicle()||!p.isAlive()){reply(p,"Нельзя перемещаться в бою, технике или во время операции с инвентарём.");return;}var member=nations.member(p.getUuid());if(member!=null&&wars.wars().stream().anyMatch(a->wars.window(a,System.currentTimeMillis())&&(a.attacker().equals(member.nation())||a.defender().equals(member.nation())))){reply(p,"Во время боевого окна телепортация отключена.");return;}teleports.put(p.getUuid(),new PendingTeleport(id,w,System.currentTimeMillis()+config.teleportWarmupSeconds*1000L,p.getBlockPos()));reply(p,"Телепортация через "+config.teleportWarmupSeconds+" сек. Не двигайтесь.");}
 public boolean protectedAt(ServerWorld w,BlockPos p){return safe(w,p)||nations.claim(w.getRegistryKey().getValue().toString(),p.getX()>>4,p.getZ()>>4)!=null;}
 public boolean safe(ServerWorld w,BlockPos p){return w.getRegistryKey().getValue().toString().equals("warland:capital")||safeZones.stream().anyMatch(s->s.world==w&&CoreSafety.withinSquare(p.getX(),p.getZ(),s.feet.getX(),s.feet.getZ(),s.radius));}
 @Override public boolean inventoryLocked(UUID player){ServerPlayerEntity p=server==null?null:server.getPlayerManager().getPlayer(player);return p!=null&&!authorized(p)||InventoryLocks.busy(player);}
 @Override public boolean canBuild(ServerPlayerEntity p,ServerWorld w,BlockPos pos){if(!authorized(p)||inventoryLocked(p.getUuid())||p.isSpectator()||w.getRegistryKey()==net.minecraft.world.World.END)return false;if(safe(w,pos))return false;String n=nations.claim(w.getRegistryKey().getValue().toString(),pos.getX()>>4,pos.getZ()>>4);return n==null||nations.allowed(p.getUuid(),n,"build");}
 @Override public boolean canBuildCity(ServerPlayerEntity p,ServerWorld w,BlockPos pos){if(!canBuild(p,w,pos))return false;var member=nations.member(p.getUuid());String claim=nations.claim(w.getRegistryKey().getValue().toString(),pos.getX()>>4,pos.getZ()>>4);return CoreSafety.cityAllowed(ready,false,member==null?null:member.nation(),claim,claim!=null&&nations.allowed(p.getUuid(),claim,"build"));}
 @Override public boolean canDamage(ServerPlayerEntity p,Entity target){if(!authorized(p)||target instanceof ServerPlayerEntity t&&!authorized(t)||inventoryLocked(p.getUuid())||p.isSpectator()||p.isCreative()||target==p||!(target.getEntityWorld() instanceof ServerWorld w)||safe(w,target.getBlockPos())||safe(p.getEntityWorld(),p.getBlockPos()))return false;String there=nations.claim(w.getRegistryKey().getValue().toString(),target.getBlockPos().getX()>>4,target.getBlockPos().getZ()>>4);String here=nations.claim(p.getEntityWorld().getRegistryKey().getValue().toString(),p.getBlockPos().getX()>>4,p.getBlockPos().getZ()>>4);if(target instanceof ServerPlayerEntity other){if(here==null&&there==null)return true;var a=nations.member(p.getUuid());var b=nations.member(other.getUuid());return a!=null&&b!=null&&wars.enemies(a.nation(),b.nation());}var a=nations.member(p.getUuid());return there==null||(a!=null&&(there.equals(a.nation())||wars.enemies(a.nation(),there)));}
 @Override public boolean inCombat(UUID p){return combat.getOrDefault(p,0L)>System.currentTimeMillis();}
 @Override public boolean staff(ServerCommandSource s,String permission){return ready&&(s.getEntity() instanceof ServerPlayerEntity p?auth.owner(p):auth.console(s));}
 @Override public void reply(ServerPlayerEntity p,String text){p.sendMessage(Text.literal("§bWarLand §8» §r"+text),false);}
 @Override public void menu(ServerPlayerEntity p,String title,List<MenuEntry> entries){if(online(p)){if(inventoryLocked(p.getUuid())){reply(p,"Дождитесь завершения операции с инвентарём.");return;}Menus.open(p,title,entries.stream().map(e->new MenuEntry(e.icon(),()->{if(online(p))e.action().run();})).toList());}}
 @Override public void audit(String actor,String action,String target){store.audit(actor,action,target).exceptionally(e->{WarLand.LOG.error("Audit failed",e);return null;});}
 @Override public CompletableFuture<Long> balance(UUID p){return store.balance(p);}
 @Override public CompletableFuture<Boolean> debit(UUID p,long amount,String op,String reason){if(!ready||amount<0||amount>1_000_000_000)return CompletableFuture.completedFuture(false);return store.money(p,-amount,op,reason);}
 @Override public CompletableFuture<Boolean> credit(UUID p,long amount,String op,String reason){if(!ready||amount<0||amount>1_000_000_000)return CompletableFuture.completedFuture(false);return store.money(p,amount,op,reason);}
 @Override public CompletableFuture<String> getState(String ns,String key){return store.state(ns,key);}
 @Override public CompletableFuture<Void> setState(String ns,String key,String json){return store.state(ns,key,json);}
 @Override public void setSafeSpawn(ServerWorld w,BlockPos feet,int radius){if(radius<0||radius>1024)throw new IllegalArgumentException("Invalid safe-zone radius");safeZones.removeIf(s->s.world==w&&s.feet.equals(feet));safeZones.add(new SafeZone(w,feet.toImmutable(),radius));registerWarp("spawn",w,feet);}
 @Override public void registerWarp(String id,ServerWorld w,BlockPos feet){warps.put(id,new Warp(w,feet.toImmutable()));}
 @Override public boolean ready(){return ready;}
 @Override public MinecraftServer server(){return server;}
 @Override public Path dataDirectory(){return dir;}
}
