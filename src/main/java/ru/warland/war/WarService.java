package ru.warland.war;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import ru.warland.core.*;
import ru.warland.data.Store;
import ru.warland.nations.NationsService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class WarService {
 public record War(String id,String attacker,String defender,long declared,long starts,long ends,String status,int attackScore,int defendScore){}
 private final Store db;private final GameConfig config;private final NationsService nations;
 private volatile List<War> wars=List.of();private final Map<String,Integer> captureSeconds=new HashMap<>();private final Set<String> settling=new HashSet<>();
 public WarService(Store db,GameConfig config,NationsService nations){this.db=db;this.config=config;this.nations=nations;}
 public List<War> wars(){return wars;}
 public boolean window(War w,long now){return w.status.equals("ACTIVE")&&Rules.combatWindow(now,w.starts,w.ends,config.warHourMoscow,config.warWindowHours);}
 public boolean enemies(String a,String b){if(a==null||b==null||a.equals(b))return false;return wars.stream().anyMatch(w->window(w,System.currentTimeMillis())&&((w.attacker.equals(a)&&w.defender.equals(b))||(w.attacker.equals(b)&&w.defender.equals(a))));}
 public CompletableFuture<Void> refresh(){return db.submit(c->{List<War> out=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM wars WHERE status='ACTIVE' OR ends>"+(System.currentTimeMillis()-config.warImmunityDays*86400000L))){while(r.next())out.add(new War(r.getString("id"),r.getString("attacker"),r.getString("defender"),r.getLong("declared"),r.getLong("starts"),r.getLong("ends"),r.getString("status"),r.getInt("attack_score"),r.getInt("defend_score")));}return List.copyOf(out);}).thenAccept(v->wars=v);}
 public CompletableFuture<String> declare(UUID p,String target){String op=UUID.randomUUID().toString();long now=System.currentTimeMillis();return db.tx(c->{
  String n=NationsService.require(c,p,"war"),other=Store.string(c,"SELECT id FROM nations WHERE name=? COLLATE NOCASE",target);
  if(other==null||n.equals(other))throw NationsService.rule("Укажите другое государство");
  if(Store.scalar(c,"SELECT COUNT(*) FROM wars WHERE status='ACTIVE' AND (attacker IN (?,?) OR defender IN (?,?))",n,other,n,other)>0)throw NationsService.rule("Одна из сторон уже участвует в войне");
  if(Store.scalar(c,"SELECT COUNT(*) FROM wars WHERE ends>? AND ((attacker=? AND defender=?) OR (attacker=? AND defender=?))",now-config.warImmunityDays*86400000L,n,other,other,n)>0)throw NationsService.rule("Действует послевоенный иммунитет");
  if(Store.scalar(c,"SELECT COUNT(*) FROM diplomacy WHERE state='PACT' AND expires>? AND ((a=? AND b=?) OR (a=? AND b=?))",now,n,other,other,n)>0)throw NationsService.rule("Действует пакт о ненападении");
  long created=Store.scalar(c,"SELECT created FROM nations WHERE id=?",other);if(now-created<86400000L)throw NationsService.rule("Новое государство защищено первые 24 часа");
  if(!config.enableWarCapture)throw NationsService.rule("Войны ещё не открыты: необходимы боевые испытания");
  if(!Store.change(c,Store.nation(n),-config.warCost,op,"war-declare"))throw NationsService.rule("В казне недостаточно средств");
  long starts=now+config.mobilizationHours*3600000L,ends=starts+config.warHours*3600000L;
  Store.update(c,"INSERT INTO wars(id,attacker,defender,declared,starts,ends,status) VALUES(?,?,?,?,?,?,'ACTIVE')",op,n,other,now,starts,ends);
  return "Война объявлена. Мобилизация 12 часов. Окна: "+config.warHourMoscow+":00–"+(config.warHourMoscow+config.warWindowHours)+":00 МСК.";
 }).thenCompose(v->refresh().thenApply(x->v));}
 public CompletableFuture<Void> initialize(){return db.tx(c->{Store.update(c,"CREATE TABLE IF NOT EXISTS war_captures(war TEXT NOT NULL,dimension TEXT NOT NULL,x INTEGER NOT NULL,z INTEGER NOT NULL,side TEXT NOT NULL,window TEXT NOT NULL,PRIMARY KEY(war,dimension,x,z))");return null;}).thenCompose(v->refresh());}
 /** Called once per second; no chunk scans, offline simulation or forced chunk loading. */
 public void tick(MinecraftServer server){
  long now=System.currentTimeMillis();
  for(War w:wars){
   if(!w.status.equals("ACTIVE"))continue;
   if(now>=w.ends){settle(w,server);continue;}
   if(!config.enableWarCapture||!window(w,now))continue;
   Map<String,List<ServerPlayerEntity>> candidates=new HashMap<>();
   for(ServerPlayerEntity p:server.getPlayerManager().getPlayerList()){
    var member=nations.member(p.getUuid());if(member==null||p.isCreative()||p.isSpectator()||!p.isAlive())continue;
    String side=member.nation();if(!side.equals(w.attacker)&&!side.equals(w.defender))continue;
    var pos=p.getBlockPos();String d=p.getEntityWorld().getRegistryKey().getValue().toString();String held=nations.claim(d,pos.getX()>>4,pos.getZ()>>4);
    String opponent=side.equals(w.attacker)?w.defender:w.attacker;if(!opponent.equals(held))continue;
    var capital=nations.snapshot().nations().get(held);if(capital==null||((capital.x()>>4)==(pos.getX()>>4)&&(capital.z()>>4)==(pos.getZ()>>4)))continue;
    if(Math.abs((pos.getX()&15)-8)>4||Math.abs((pos.getZ()&15)-8)>4)continue;
    int x=pos.getX()>>4,z=pos.getZ()>>4;
    if(!side.equals(nations.claim(d,x+1,z))&&!side.equals(nations.claim(d,x-1,z))&&!side.equals(nations.claim(d,x,z+1))&&!side.equals(nations.claim(d,x,z-1)))continue;
    String key=w.id+"|"+side+"|"+d+"|"+x+"|"+z;candidates.computeIfAbsent(key,k->new ArrayList<>()).add(p);
   }
   Set<String> active=new HashSet<>();
   for(var e:candidates.entrySet()){
    String key=e.getKey();String[] parts=key.split("\\|");String side=parts[1],d=parts[2];int x=Integer.parseInt(parts[3]),z=Integer.parseInt(parts[4]);String opponent=side.equals(w.attacker)?w.defender:w.attacker;
    boolean contested=server.getPlayerManager().getPlayerList().stream().anyMatch(p->{var m=nations.member(p.getUuid());return m!=null&&m.nation().equals(opponent)&&p.isAlive()&&!p.isSpectator()&&p.getEntityWorld().getRegistryKey().getValue().toString().equals(d)&&p.getBlockPos().getX()>>4==x&&p.getBlockPos().getZ()>>4==z;});
    if(contested)continue;
    boolean defenderOnline=server.getPlayerManager().getPlayerList().stream().anyMatch(p->{var m=nations.member(p.getUuid());return m!=null&&m.nation().equals(opponent)&&!p.isSpectator();});
    active.add(key);int increment=defenderOnline?4:1;int points=captureSeconds.merge(key,increment,Integer::sum);
    if(points>=480&&!settling.contains(key)){settling.add(key);capture(w,side,d,x,z).whenComplete((result,error)->server.execute(()->{settling.remove(key);captureSeconds.remove(key);if(error==null)server.getPlayerManager().broadcast(net.minecraft.text.Text.literal("[WarLand] Захвачена пограничная цель: "+x+", "+z),false);}));}
   }
   captureSeconds.keySet().removeIf(k->k.startsWith(w.id+"|")&&!active.contains(k)&&!settling.contains(k));
  }
 }
 private CompletableFuture<Void> capture(War w,String side,String d,int x,int z){return db.tx(c->{
  long now=System.currentTimeMillis();if(!window(w,now))throw NationsService.rule("Окно закрыто");String date=Rules.day(now);
  if(Store.scalar(c,"SELECT COUNT(*) FROM war_captures WHERE war=? AND side=? AND window=?",w.id,side,date)>=3)throw NationsService.rule("Лимит захватов за окно");
  if(Store.scalar(c,"SELECT COUNT(*) FROM war_captures WHERE war=? AND dimension=? AND x=? AND z=?",w.id,d,x,z)>0)throw NationsService.rule("Цель уже зачтена");
  String old=side.equals(w.attacker)?w.defender:w.attacker;
  if(Store.update(c,"UPDATE claims SET nation=? WHERE dimension=? AND x=? AND z=? AND nation=?",side,d,x,z,old)!=1)throw NationsService.rule("Цель изменилась");
  Store.update(c,"INSERT INTO war_captures(war,dimension,x,z,side,window) VALUES(?,?,?,?,?,?)",w.id,d,x,z,side,date);
  Store.update(c,side.equals(w.attacker)?"UPDATE wars SET attack_score=attack_score+20 WHERE id=?":"UPDATE wars SET defend_score=defend_score+20 WHERE id=?",w.id);return null;
 }).thenCompose(v->nations.refresh()).thenCompose(v->refresh());}
 private void settle(War w,MinecraftServer server){if(!settling.add(w.id))return;db.tx(c->{
  String status=Math.abs(w.attackScore-w.defendScore)<20?"DRAW":w.attackScore>w.defendScore?"ATTACKER_WIN":"DEFENDER_WIN";
  Store.update(c,"UPDATE wars SET status=? WHERE id=? AND status='ACTIVE'",status,w.id);return status;
 }).thenCompose(v->refresh()).whenComplete((v,e)->server.execute(()->settling.remove(w.id)));}
}
