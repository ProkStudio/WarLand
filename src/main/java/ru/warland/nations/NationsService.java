package ru.warland.nations;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import ru.warland.core.*;
import ru.warland.data.Store;

public final class NationsService {
 public record Nation(String id,String name,String owner,int x,int y,int z,String dimension,int level,int tax){}
 public record Member(String nation,String rank){}
 public record Chunk(String dimension,int x,int z){}
 public record Snapshot(Map<String,Nation> nations,Map<UUID,Member> members,Map<Chunk,String> claims,Map<String,Map<String,Set<String>>> ranks){}
 private final Store db; private final GameConfig config;
 private final Function<UUID,BooleanSupplier> actorLeases;
 private volatile Snapshot cache=new Snapshot(Map.of(),Map.of(),Map.of(),Map.of());
 private static final Set<String> PERMISSIONS=Set.of("build","claim","invite","treasury","war","buildings","diplomacy");
 /** Explicit trusted system/test context. Production must supply its session lease factory. */
 public NationsService(Store db,GameConfig config){this(db,config,id->()->true);}
 public NationsService(Store db,GameConfig config,Function<UUID,BooleanSupplier> actorLeases){this.db=db;this.config=config;this.actorLeases=Objects.requireNonNull(actorLeases);}
 public Snapshot snapshot(){return cache;}
 public Member member(UUID p){return cache.members.get(p);}
 public Nation nation(UUID p){Member m=member(p);return m==null?null:cache.nations.get(m.nation);}
 public String claim(String d,int x,int z){return cache.claims.get(new Chunk(d,x,z));}
 public boolean allowed(UUID player,String nation,String permission){Member m=member(player);if(m==null||!m.nation.equals(nation))return false;Nation n=cache.nations.get(nation);if(n!=null&&n.owner.equals(player.toString()))return true;return cache.ranks.getOrDefault(nation,defaults()).getOrDefault(m.rank,Set.of()).contains(permission);}
 public static Map<String,Set<String>> defaults(){return Map.of("LEADER",PERMISSIONS,"OFFICER",Set.of("build","claim","invite","buildings","diplomacy"),"BUILDER",Set.of("build","buildings"),"MEMBER",Set.of("build"));}
 public CompletableFuture<Void> refresh(){return db.submit(c->{
  Map<String,Nation> ns=new HashMap<>();Map<UUID,Member> ms=new HashMap<>();Map<Chunk,String> cs=new HashMap<>();Map<String,Map<String,Set<String>>> rs=new HashMap<>();
  try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM nations")){while(r.next()){Nation n=new Nation(r.getString("id"),r.getString("name"),r.getString("owner"),r.getInt("cx"),r.getInt("cy"),r.getInt("cz"),r.getString("dimension"),r.getInt("level"),r.getInt("tax"));ns.put(n.id,n);rs.put(n.id,readRanks(c,n.id));}}
  try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM members")){while(r.next())ms.put(UUID.fromString(r.getString("uuid")),new Member(r.getString("nation"),r.getString("rank")));}
  try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM claims")){while(r.next())cs.put(new Chunk(r.getString("dimension"),r.getInt("x"),r.getInt("z")),r.getString("nation"));}
  return new Snapshot(Map.copyOf(ns),Map.copyOf(ms),Map.copyOf(cs),Map.copyOf(rs));
 }).thenAccept(s->cache=s).copy();}
 private CompletableFuture<String> mutate(UUID actor,Store.Work<String> w){
  final BooleanSupplier lease;
  try{lease=Objects.requireNonNull(actorLeases.apply(actor),"actor lease");}
  catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
  // The factory runs at action entry; the worker checks that exact captured session.
  // An observer cancelling its future must not cancel committed cache publication.
  return db.tx(lease,w).thenCompose(result->refresh().thenApply(v->result)).copy();
 }
 public CompletableFuture<String> create(UUID p,String name,String d,int x,int y,int z){
  if(!Rules.validNation(name))return CompletableFuture.failedFuture(new IllegalArgumentException("Название: 3–20 букв, цифр, _ или -"));
  if(!"minecraft:overworld".equals(d))return CompletableFuture.failedFuture(rule("Государственные территории доступны только в Верхнем мире"));
  String id=UUID.randomUUID().toString(),op=UUID.randomUUID().toString();
  return mutate(p,c->{
   if(Store.scalar(c,"SELECT COUNT(*) FROM members WHERE uuid=?",p.toString())!=0)throw rule("Вы уже состоите в государстве");
   if(Store.scalar(c,"SELECT COUNT(*) FROM profiles WHERE uuid=? AND tutorial=1",p.toString())==0)throw rule("Сначала пройдите /warland tutorial");
   if(Store.scalar(c,"SELECT COUNT(*) FROM nations WHERE name=? COLLATE NOCASE",name)>0)throw rule("Это название занято");
   if(Store.scalar(c,"SELECT COUNT(*) FROM claims WHERE dimension=? AND ABS(x-?)<=1 AND ABS(z-?)<=1",d,x>>4,z>>4)>0)throw rule("Оставьте один свободный чанк до чужой территории");
   if(!Store.change(c,Store.player(p),-config.nationCost,op,"nation-create"))throw rule("Недостаточно денег");
   Store.update(c,"INSERT INTO nations(id,name,owner,cx,cy,cz,dimension,created) VALUES(?,?,?,?,?,?,?,?)",id,name,p.toString(),x,y,z,d,System.currentTimeMillis());
   Store.update(c,"INSERT INTO members(uuid,nation,rank,joined) VALUES(?,?,'LEADER',?)",p.toString(),id,System.currentTimeMillis());Store.account(c,Store.nation(id));
   Store.update(c,"INSERT INTO claims(dimension,x,z,nation,created) VALUES(?,?,?,?,?)",d,x>>4,z>>4,id,System.currentTimeMillis());
   return "Государство «"+name+"» создано. Столичный чанк включён в цену.";
  });
 }
 public CompletableFuture<String> claim(UUID p,String d,int x,int z){String op=UUID.randomUUID().toString();return mutate(p,c->{
  String n=require(c,p,"claim");if(!d.equals("minecraft:overworld"))throw rule("Государственные территории доступны только в Верхнем мире");
  if(Store.scalar(c,"SELECT COUNT(*) FROM wars WHERE status='ACTIVE' AND (attacker=? OR defender=?)",n,n)>0)throw rule("Во время войны расширение заблокировано");
  if(Store.scalar(c,"SELECT COUNT(*) FROM claims WHERE dimension=? AND x=? AND z=?",d,x,z)>0)throw rule("Чанк уже занят");
  if(Store.scalar(c,"SELECT COUNT(*) FROM claims WHERE dimension=? AND nation=? AND ABS(x-?)+ABS(z-?)=1",d,n,x,z)==0)throw rule("Новый чанк должен примыкать к вашей территории");
  int active=(int)Store.scalar(c,"SELECT COUNT(*) FROM members m JOIN profiles p ON m.uuid=p.uuid WHERE m.nation=? AND p.last_seen>?",n,System.currentTimeMillis()-7*86400000L);
  int level=(int)Store.scalar(c,"SELECT level FROM nations WHERE id=?",n);
  if(Store.scalar(c,"SELECT COUNT(*) FROM claims WHERE nation=?",n)>=Rules.claimLimit(active,level))throw rule("Достигнут лимит чанков");
  if(!Store.change(c,Store.nation(n),-config.claimCost,op,"claim"))throw rule("Пополните казну: /nation deposit <сумма>");
  Store.update(c,"INSERT INTO claims(dimension,x,z,nation,created) VALUES(?,?,?,?,?)",d,x,z,n,System.currentTimeMillis());return "Чанк "+x+", "+z+" теперь принадлежит государству.";
 });}
 public CompletableFuture<String> invite(UUID actor,UUID target){return mutate(actor,c->{String n=require(c,actor,"invite");if(Store.scalar(c,"SELECT COUNT(*) FROM members WHERE uuid=?",target.toString())>0)throw rule("Игрок уже состоит в государстве");Store.update(c,"INSERT INTO invites(nation,uuid,expires) VALUES(?,?,?) ON CONFLICT(nation,uuid) DO UPDATE SET expires=excluded.expires",n,target.toString(),System.currentTimeMillis()+600000);return "Приглашение отправлено на 10 минут.";});}
 public CompletableFuture<String> join(UUID p,String name){return mutate(p,c->{String n=Store.string(c,"SELECT id FROM nations WHERE name=? COLLATE NOCASE",name);if(n==null)throw rule("Государство не найдено");if(Store.scalar(c,"SELECT COUNT(*) FROM members WHERE uuid=?",p.toString())>0)throw rule("Вы уже в государстве");if(Store.scalar(c,"SELECT COUNT(*) FROM invites WHERE nation=? AND uuid=? AND expires>?",n,p.toString(),System.currentTimeMillis())==0)throw rule("Нет действующего приглашения");if(Store.scalar(c,"SELECT COUNT(*) FROM wars WHERE status='ACTIVE' AND (attacker=? OR defender=?)",n,n)>0)throw rule("Состав участников заморожен на время войны");Store.update(c,"INSERT INTO members(uuid,nation,rank,joined) VALUES(?,?,'MEMBER',?)",p.toString(),n,System.currentTimeMillis());Store.update(c,"DELETE FROM invites WHERE uuid=?",p.toString());return "Вы вступили в «"+name+"».";});}
 public CompletableFuture<String> leave(UUID p){return mutate(p,c->{String n=requireMember(c,p);if(p.toString().equals(Store.string(c,"SELECT owner FROM nations WHERE id=?",n)))throw rule("Лидер не может покинуть государство");if(Store.scalar(c,"SELECT COUNT(*) FROM wars WHERE status='ACTIVE' AND (attacker=? OR defender=?)",n,n)>0)throw rule("Нельзя выйти во время войны");Store.update(c,"DELETE FROM members WHERE uuid=?",p.toString());return "Вы покинули государство.";});}
 public CompletableFuture<String> transfer(UUID p,long amount,boolean withdrawal){if(amount<1||amount>1000000000)return CompletableFuture.failedFuture(rule("Сумма вне допустимого диапазона"));String op=UUID.randomUUID().toString();return mutate(p,c->{String n=withdrawal?require(c,p,"treasury"):requireMember(c,p);String from=withdrawal?Store.nation(n):Store.player(p),to=withdrawal?Store.player(p):Store.nation(n);if(!Store.change(c,from,-amount,op,"treasury"))throw rule("Недостаточно средств");if(!Store.change(c,to,amount,op,"treasury"))throw rule("Лимит счёта");return (withdrawal?"Выведено из казны: ":"Внесено в казну: ")+amount;});}
 public CompletableFuture<String> tax(UUID p,int rate){return mutate(p,c->{String n=requireLeader(c,p);if(rate<0||rate>config.maxTaxPercent)throw rule("Налог от 0 до "+config.maxTaxPercent+"%");Store.update(c,"UPDATE nations SET pending_tax=?,effective_tax=? WHERE id=?",rate,System.currentTimeMillis()+86400000L,n);return "Налог "+rate+"% вступит в силу через 24 часа.";});}
 public CompletableFuture<String> rank(UUID leader,UUID target,String rank){return mutate(leader,c->{String n=requireLeader(c,leader);if(target.equals(leader))throw rule("Нельзя менять собственный ранг лидера");if(!readRanks(c,n).containsKey(rank)||rank.equals("LEADER"))throw rule("Нет такого ранга");if(Store.update(c,"UPDATE members SET rank=? WHERE uuid=? AND nation=?",rank,target.toString(),n)==0)throw rule("Игрок не является участником");return "Ранг установлен: "+rank;});}
 public CompletableFuture<String> defineRank(UUID leader,String rank,String permissions){return mutate(leader,c->{String n=requireLeader(c,leader);if(!rank.matches("[A-Za-z_]{3,20}")||rank.equals("LEADER"))throw rule("Ранг: 3–20 латинских букв, кроме LEADER");Set<String> ps=new HashSet<>(Arrays.asList(permissions.split(",")));if(!PERMISSIONS.containsAll(ps))throw rule("Права: "+String.join(",",PERMISSIONS));Map<String,Set<String>> ranks=new HashMap<>(readRanks(c,n));if(ranks.size()>=12&&!ranks.containsKey(rank))throw rule("Не более 12 рангов");ranks.put(rank,Set.copyOf(ps));String json=new Gson().toJson(ranks);Store.update(c,"INSERT INTO state(namespace,key,json) VALUES('ranks',?,?) ON CONFLICT(namespace,key) DO UPDATE SET json=excluded.json",n,json);return "Ранг "+rank+" сохранён.";});}
 public CompletableFuture<String> pact(UUID p,String target){return mutate(p,c->{String n=require(c,p,"diplomacy"),other=Store.string(c,"SELECT id FROM nations WHERE name=? COLLATE NOCASE",target);if(other==null||other.equals(n))throw rule("Укажите другое государство");if(Store.scalar(c,"SELECT COUNT(*) FROM wars WHERE status='ACTIVE' AND ((attacker=? AND defender=?) OR (attacker=? AND defender=?))",n,other,other,n)>0)throw rule("Нельзя заключить пакт во время войны");String a=n.compareTo(other)<0?n:other,b=n.compareTo(other)<0?other:n;if(Store.scalar(c,"SELECT COUNT(*) FROM diplomacy WHERE a=? AND b=? AND state='PACT' AND expires>?",a,b,System.currentTimeMillis())>0)throw rule("Пакт уже действует");String offered=Store.string(c,"SELECT offered_by FROM diplomacy WHERE a=? AND b=? AND state='OFFER' AND expires>?",a,b,System.currentTimeMillis());String state=offered!=null&&!offered.equals(n)?"PACT":"OFFER";Store.update(c,"INSERT INTO diplomacy(a,b,state,offered_by,expires) VALUES(?,?,?,?,?) ON CONFLICT(a,b) DO UPDATE SET state=excluded.state,offered_by=excluded.offered_by,expires=excluded.expires",a,b,state,n,System.currentTimeMillis()+7*86400000L);return state.equals("PACT")?"Пакт о ненападении заключён на 7 дней.":"Предложение отправлено. Другая сторона подтверждает той же командой.";});}
 public CompletableFuture<Void> applyTaxes(){return db.tx(c->{Store.update(c,"UPDATE nations SET tax=pending_tax,pending_tax=NULL,effective_tax=NULL WHERE effective_tax<=?",System.currentTimeMillis());return null;}).thenCompose(v->refresh()).copy();}
 public static SQLException rule(String message){return new SQLException(message);}
 public static String requireMember(Connection c,UUID p)throws SQLException{String n=Store.string(c,"SELECT nation FROM members WHERE uuid=?",p.toString());if(n==null)throw rule("Вы не состоите в государстве");return n;}
 public static String requireLeader(Connection c,UUID p)throws SQLException{String n=requireMember(c,p);if(!p.toString().equals(Store.string(c,"SELECT owner FROM nations WHERE id=?",n)))throw rule("Доступно только лидеру");return n;}
 public static String require(Connection c,UUID p,String perm)throws SQLException{String n=requireMember(c,p);if(p.toString().equals(Store.string(c,"SELECT owner FROM nations WHERE id=?",n)))return n;String rank=Store.string(c,"SELECT rank FROM members WHERE uuid=?",p.toString());if(!readRanks(c,n).getOrDefault(rank,Set.of()).contains(perm))throw rule("Недостаточно прав: "+perm);return n;}
 private static Map<String,Set<String>> readRanks(Connection c,String n)throws SQLException{Map<String,Set<String>> out=new HashMap<>(defaults());String s=Store.string(c,"SELECT json FROM state WHERE namespace='ranks' AND key=?",n);if(s!=null){JsonObject o=JsonParser.parseString(s).getAsJsonObject();for(var e:o.entrySet()){Set<String> ps=new HashSet<>();for(JsonElement p:e.getValue().getAsJsonArray())if(PERMISSIONS.contains(p.getAsString()))ps.add(p.getAsString());if(!e.getKey().equals("LEADER"))out.put(e.getKey(),Set.copyOf(ps));}}return Map.copyOf(out);}
}
