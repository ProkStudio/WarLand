package ru.warland.moderation;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.item.Items;
import ru.warland.WarLand;
import ru.warland.core.CoreRuntime;
import ru.warland.data.Store;
import ru.warland.api.WarLandApi.MenuEntry;
import ru.warland.ui.Menus;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static net.minecraft.server.command.CommandManager.*;

/** Fixed roles; only successfully persisted snapshots authorize players. */
public final class Moderation {
 public enum Role {MODERATOR,CURATOR,TECHADMIN}
 public record Sanction(String kind,String reason,long expires,String actor) {boolean active(){return expires==0||expires>System.currentTimeMillis();}}
 public static final class State {
  public int schemaVersion=1;
  public Map<String,Role> roles=new HashMap<>();
  public Map<String,Sanction> bans=new HashMap<>(),mutes=new HashMap<>();
 }
 private final CoreRuntime r;private final Gson gson=new Gson();private volatile State state=new State();
 private volatile boolean loaded,busy,closed;
 private Moderation(CoreRuntime r){this.r=r;}
 public static void register(CoreRuntime r){new Moderation(r).initialize();}
 private boolean owner(ServerCommandSource s){return r.staff(s,"owner");}
 private boolean canModerate(ServerCommandSource s){if(owner(s))return true;if(!loaded||!(s.getEntity() instanceof ServerPlayerEntity p))return false;Role role=state.roles.get(p.getUuid().toString());return role==Role.MODERATOR||role==Role.CURATOR;}
 private boolean canView(ServerCommandSource s){return canModerate(s)||(loaded&&s.getEntity() instanceof ServerPlayerEntity p&&state.roles.get(p.getUuid().toString())==Role.TECHADMIN);}
 private void feedback(ServerCommandSource s,String text){s.sendFeedback(()->Text.literal("[WarLand] "+text),false);}
 private static String actor(ServerCommandSource s){return s.getEntity() instanceof ServerPlayerEntity p?p.getUuidAsString():"console";}
 private void initialize(){
  r.modules.add("Moderation");
  ServerLifecycleEvents.SERVER_STOPPING.register(s->{closed=true;loaded=false;});
  ServerLifecycleEvents.SERVER_STARTED.register(s->r.getState("moderation","state").whenComplete((json,error)->s.execute(()->{
   if(closed)return;
   try {if(error!=null)throw new IllegalStateException(error);state=decode(json);loaded=true;}
   catch(RuntimeException invalid){loaded=false;WarLand.LOG.error("Moderation locked: invalid or unreadable durable state; original retained",invalid);}
  })));
  ServerPlayConnectionEvents.JOIN.register((h,sender,s)->{if(!loaded){h.player.networkHandler.disconnect(Text.literal("Проверка доступа ещё запускается или закрыта после ошибки хранения."));return;}Sanction ban=state.bans.get(h.player.getUuid().toString());if(ban!=null&&ban.active())h.player.networkHandler.disconnect(Text.literal("Блокировка: "+ban.reason));});
  ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message,p,params)->canChat(p));
  ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message,source,params)->!(source.getEntity() instanceof ServerPlayerEntity p)||canChat(p));
  CommandRegistrationCallback.EVENT.register((d,registries,env)->{
   var root=literal("staff").requires(this::canView).executes(c->{menu(c.getSource());return 1;});
   root.then(literal("reports").requires(this::canModerate).executes(c->{reports(c.getSource());return 1;}));
   root.then(literal("resolve").requires(this::canModerate).then(argument("id",IntegerArgumentType.integer(1)).then(argument("reason",StringArgumentType.greedyString()).executes(c->{resolve(c.getSource(),IntegerArgumentType.getInteger(c,"id"),StringArgumentType.getString(c,"reason"));return 1;}))));
   root.then(literal("diag").executes(c->{if(!canView(c.getSource()))return 0;feedback(c.getSource(),"Готовность: "+r.ready()+", средний тик: "+r.server().getAverageTickTime()+" мс");r.audit(actor(c.getSource()),"diagnostics","server");return 1;}));
   root.then(literal("role").requires(this::owner).then(argument("uuid",StringArgumentType.word()).then(argument("role",StringArgumentType.word()).executes(c->{var s=c.getSource();try{UUID id=UUID.fromString(StringArgumentType.getString(c,"uuid"));Role role=Role.valueOf(StringArgumentType.getString(c,"role").toUpperCase(Locale.ROOT));if(!owner(s)||!begin(s))return 0;State next=copy();next.roles.put(id.toString(),role);persist(s,next,"staff-role",id+" "+role,()->feedback(s,"Роль назначена: "+role));}catch(IllegalArgumentException e){feedback(s,"Укажите UUID и MODERATOR, CURATOR или TECHADMIN");}return 1;}))));
   root.then(literal("revoke").requires(this::owner).then(argument("uuid",StringArgumentType.word()).executes(c->{var s=c.getSource();try{UUID id=UUID.fromString(StringArgumentType.getString(c,"uuid"));if(!owner(s)||!begin(s))return 0;State next=copy();next.roles.remove(id.toString());persist(s,next,"staff-revoke",id.toString(),()->feedback(s,"Роль снята"));}catch(IllegalArgumentException e){feedback(s,"Некорректный UUID");}return 1;})));
   for(String kind:List.of("ban","mute"))root.then(literal(kind).requires(this::canModerate).then(argument("player",StringArgumentType.word()).then(argument("minutes",IntegerArgumentType.integer(0,525600)).then(argument("reason",StringArgumentType.greedyString()).executes(c->{punish(c.getSource(),StringArgumentType.getString(c,"player"),kind,IntegerArgumentType.getInteger(c,"minutes"),StringArgumentType.getString(c,"reason"));return 1;})))));
   root.then(literal("kick").requires(this::canModerate).then(argument("player",StringArgumentType.word()).then(argument("reason",StringArgumentType.greedyString()).executes(c->{var s=c.getSource();if(!canModerate(s))return 0;var p=r.server().getPlayerManager().getPlayer(StringArgumentType.getString(c,"player"));String reason=StringArgumentType.getString(c,"reason");if(p==null||!validText(reason,300)||protectedTarget(s,p.getUuid())){feedback(s,"Цель недоступна или причина некорректна");return 0;}r.audit(actor(s),"kick",p.getUuid()+" "+reason);p.networkHandler.disconnect(Text.literal(reason));feedback(s,"Игрок отключён");return 1;}))));
   for(String kind:List.of("unban","unmute"))root.then(literal(kind).requires(this::canModerate).then(argument("player",StringArgumentType.word()).then(argument("reason",StringArgumentType.greedyString()).executes(c->{var s=c.getSource();if(!canModerate(s))return 0;UUID id=target(StringArgumentType.getString(c,"player"));String reason=StringArgumentType.getString(c,"reason");if(id==null||!validText(reason,300)||protectedTarget(s,id)){feedback(s,"Укажите онлайн-имя или UUID и причину до 300 символов");return 0;}if(!begin(s))return 0;State next=copy();(kind.equals("unban")?next.bans:next.mutes).remove(id.toString());persist(s,next,kind,id+" "+reason,()->feedback(s,"Наказание снято"));return 1;}))));
   d.register(root);
  });
 }
 private boolean canChat(ServerPlayerEntity p){if(!loaded)return false;Sanction mute=state.mutes.get(p.getUuid().toString());if(mute!=null&&mute.active()){r.reply(p,"Чат ограничен: "+mute.reason);return false;}return true;}
 private UUID target(String arg){var online=r.server().getPlayerManager().getPlayer(arg);if(online!=null)return online.getUuid();try{return UUID.fromString(arg);}catch(IllegalArgumentException e){return null;}}
 private boolean protectedTarget(ServerCommandSource s,UUID id){if(owner(s))return false;if(state.roles.containsKey(id.toString()))return true;var p=r.server().getPlayerManager().getPlayer(id);return p!=null&&owner(p.getCommandSource());}
 private void punish(ServerCommandSource s,String name,String kind,int minutes,String reason){
  if(!canModerate(s))return;UUID id=target(name);
  if(id==null||!validText(reason,300)||protectedTarget(s,id)){feedback(s,"Укажите онлайн-имя или UUID, допустимую цель и причину до 300 символов");return;}
  Role role=s.getEntity() instanceof ServerPlayerEntity p?state.roles.get(p.getUuid().toString()):null;
  if(!owner(s)&&role!=Role.CURATOR&&(minutes==0||minutes>1440)){feedback(s,"Модератор может назначать наказания до 24 часов");return;}
  if(!begin(s))return;State next=copy();Sanction sanction=new Sanction(kind,reason,minutes==0?0:System.currentTimeMillis()+minutes*60000L,actor(s));
  (kind.equals("ban")?next.bans:next.mutes).put(id.toString(),sanction);
  persist(s,next,kind,id+" "+minutes+"m "+reason,()->{var p=r.server().getPlayerManager().getPlayer(id);if(p!=null){if(kind.equals("ban"))p.networkHandler.disconnect(Text.literal("Блокировка: "+reason));else r.reply(p,"Вы получили мут: "+reason);}feedback(s,"Наказание сохранено");});
 }
 private boolean begin(ServerCommandSource s){if(!loaded||busy||closed||!r.ready()||!canModerate(s)){feedback(s,"Хранилище недоступно или занято предыдущей операцией");return false;}busy=true;return true;}
 private State copy(){return gson.fromJson(gson.toJson(state),State.class);}
 private void persist(ServerCommandSource s,State next,String action,String detail,Runnable done){
  final String json;try{validate(next);json=gson.toJson(next);if(json.length()>2_000_000)throw new IllegalArgumentException("Size limit");}
  catch(RuntimeException invalid){busy=false;feedback(s,"Изменение отклонено проверкой состояния; прежние данные сохранены");return;}
  String by=actor(s);long now=System.currentTimeMillis();
  CompletableFuture<Void> write=r.store.tx(db->{
   Store.update(db,"INSERT INTO state(namespace,key,json) VALUES('moderation','state',?) ON CONFLICT(namespace,key) DO UPDATE SET json=excluded.json",json);
   Store.update(db,"INSERT INTO audit(actor,action,target,created) VALUES(?,?,?,?)",by,action,detail,now);return null;
  });
  ModerationPublication.publish(write,r.server()::execute,()->{if(closed)return;state=next;busy=false;done.run();},error->{
   loaded=false;busy=false;WarLand.LOG.error("Moderation write not acknowledged; locked until verified reload",error);
   if(!closed)feedback(s,"Запись не подтверждена. Модерация закрыта до перезапуска и проверки; повтор запрещён.");
  }).exceptionally(error->{loaded=false;busy=false;WarLand.LOG.error("Moderation publication/notification failed; durable state must be reloaded",error);return null;});
 }
 private void resolve(ServerCommandSource source,int id,String reason){
  if(!canModerate(source)||!r.ready())return;
  if(!validText(reason,300)||reason.strip().length()<8){feedback(source,"Причина закрытия: 8–300 символов без управляющих знаков");return;}
  String by=actor(source);long now=System.currentTimeMillis();
  r.store.tx(db->{int rows=Store.update(db,"UPDATE reports SET status='CLOSED' WHERE id=? AND status='OPEN'",id);
   if(rows==1)Store.update(db,"INSERT INTO audit(actor,action,target,created) VALUES(?,?,?,?)",by,"report-resolve",id+" "+reason,now);return rows;
  }).whenComplete((rows,error)->r.server().execute(()->{if(!canModerate(source))return;feedback(source,error==null&&rows==1?"Жалоба закрыта с сохранённой причиной":"Жалоба не открыта или запись не подтверждена");}));
 }
 private void reports(ServerCommandSource source){
  if(!canModerate(source)||!r.ready())return;
  r.store.submit(c->{List<String> out=new ArrayList<>();try(var p=c.prepareStatement("SELECT id,target,body FROM reports WHERE status='OPEN' ORDER BY id LIMIT 20");var rs=p.executeQuery()){while(rs.next())out.add("#"+rs.getInt(1)+" • "+rs.getString(2)+": "+rs.getString(3));}return out;})
   .whenComplete((lines,error)->r.server().execute(()->{if(!canModerate(source))return;if(error!=null){feedback(source,"Ошибка чтения жалоб");return;}if(lines.isEmpty())feedback(source,"Открытых жалоб нет");else lines.forEach(t->feedback(source,t));}));
  r.audit(actor(source),"reports-read","queue");
 }
 private void menu(ServerCommandSource s){if(!canView(s))return;if(s.getEntity() instanceof ServerPlayerEntity p){List<MenuEntry> entries=new ArrayList<>();entries.add(new MenuEntry(Menus.icon(Items.COMPARATOR,"Диагностика","Состояние и нагрузка"),()->{if(canView(s))feedback(s,"Средний тик: "+r.server().getAverageTickTime()+" мс");}));if(canModerate(s)){entries.add(new MenuEntry(Menus.icon(Items.PAPER,"Жалобы","Список открытых обращений"),()->reports(s)));entries.add(new MenuEntry(Menus.icon(Items.BOOK,"Наказания","/staff ban|mute <игрок> <минуты> <причина>","/staff resolve <id> <причина>","0 минут — навсегда, только куратор"),()->{if(canModerate(s))feedback(s,"Снятие: /staff unban|unmute <игрок/UUID> <причина>");}));}r.menu(p,"Персонал",entries);}else feedback(s,"/staff reports | resolve <id> <причина> | diag | role <uuid> <роль> | ban | mute | kick | unban | unmute");}
 static State decode(String json){
  if(json==null){State empty=new State();validate(empty);return empty;}
  if(json.isBlank()||json.length()>2_000_000)throw new IllegalArgumentException("Invalid moderation document size");
  var root=com.google.gson.JsonParser.parseString(json);if(!root.isJsonObject())throw new IllegalArgumentException("Expected object");
  var version=root.getAsJsonObject().get("schemaVersion");
  if(version!=null&&(!version.isJsonPrimitive()||!version.getAsJsonPrimitive().isNumber()||version.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE)!=0))throw new IllegalArgumentException("Unsupported schema");
  State result=new Gson().fromJson(root,State.class);validate(result);return result;
 }
 static void validate(State value){
  if(value==null||value.schemaVersion!=1||value.roles==null||value.bans==null||value.mutes==null||value.roles.size()>256||value.bans.size()>10000||value.mutes.size()>10000)throw new IllegalArgumentException("Invalid moderation schema or bounds");
  value.roles.forEach((id,role)->{canonicalId(id);if(role==null)throw new IllegalArgumentException("Unknown role");});
  checkSanctions(value.bans,"ban");checkSanctions(value.mutes,"mute");
  value.roles=Map.copyOf(value.roles);value.bans=Map.copyOf(value.bans);value.mutes=Map.copyOf(value.mutes);
 }
 private static void checkSanctions(Map<String,Sanction> values,String kind){values.forEach((id,s)->{canonicalId(id);if(s==null||!kind.equals(s.kind)||!validText(s.reason,300)||!validText(s.actor,100)||s.expires<0)throw new IllegalArgumentException("Invalid sanction");});}
 private static void canonicalId(String id){if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Noncanonical UUID");}
 static boolean validText(String text,int limit){return text!=null&&!text.isBlank()&&text.length()<=limit&&text.codePoints().noneMatch(c->c<32||c==127);}
}
