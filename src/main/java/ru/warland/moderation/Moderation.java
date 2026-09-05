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
import ru.warland.core.CoreRuntime;
import ru.warland.data.Store;
import ru.warland.api.WarLandApi.MenuEntry;
import ru.warland.ui.Menus;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static net.minecraft.server.command.CommandManager.*;

/** Fixed, least-privilege roles. Vanilla operator permissions are never granted automatically. */
public final class Moderation {
 public enum Role {MODERATOR,CURATOR,TECHADMIN}
 public record Sanction(String kind,String reason,long expires,String actor) {boolean active(){return expires==0||expires>System.currentTimeMillis();}}
 public static final class State {public Map<String,Role> roles=new HashMap<>();public Map<String,Sanction> bans=new HashMap<>();public Map<String,Sanction> mutes=new HashMap<>();}
 private final CoreRuntime r;private final Gson gson=new Gson();private State state=new State();private boolean loaded,busy;
 private Moderation(CoreRuntime r){this.r=r;}
 public static void register(CoreRuntime r){new Moderation(r).initialize();}
 private boolean owner(ServerCommandSource s){return r.staff(s,"owner");}
 private boolean canModerate(ServerCommandSource s){if(owner(s))return true;if(!loaded||!(s.getEntity() instanceof ServerPlayerEntity p))return false;Role role=state.roles.get(p.getUuid().toString());return role==Role.MODERATOR||role==Role.CURATOR;}
 private boolean canView(ServerCommandSource s){return canModerate(s)||(loaded&&s.getEntity() instanceof ServerPlayerEntity p&&state.roles.get(p.getUuid().toString())==Role.TECHADMIN);}
 private void feedback(ServerCommandSource s,String text){s.sendFeedback(()->Text.literal("[WarLand] "+text),false);}
 private CompletableFuture<Void> save(){return r.setState("moderation","state",gson.toJson(state));}
 private void initialize(){
  r.modules.add("Moderation");
  ServerLifecycleEvents.SERVER_STARTED.register(s->r.getState("moderation","state").whenComplete((json,error)->s.execute(()->{if(error!=null)return;State parsed=json==null?new State():gson.fromJson(json,State.class);if(parsed!=null&&parsed.roles!=null&&parsed.bans!=null&&parsed.mutes!=null){state=parsed;loaded=true;}})));
  ServerPlayConnectionEvents.JOIN.register((h,sender,s)->{if(!loaded){h.player.networkHandler.disconnect(Text.literal("Проверка доступа ещё запускается. Повторите вход."));return;}Sanction ban=state.bans.get(h.player.getUuid().toString());if(ban!=null&&ban.active())h.player.networkHandler.disconnect(Text.literal("Блокировка: "+ban.reason));});
  ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message,p,params)->canChat(p));
  ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message,source,params)->!(source.getEntity() instanceof ServerPlayerEntity p)||canChat(p));
  CommandRegistrationCallback.EVENT.register((d,registries,env)->{
   var root=literal("staff").requires(this::canView).executes(c->{menu(c.getSource());return 1;});
   root.then(literal("reports").requires(this::canModerate).executes(c->{reports(c.getSource());return 1;}));
   root.then(literal("resolve").requires(this::canModerate).then(argument("id",IntegerArgumentType.integer(1)).executes(c->{int id=IntegerArgumentType.getInteger(c,"id");var s=c.getSource();r.store.tx(db->Store.update(db,"UPDATE reports SET status='CLOSED' WHERE id=?",id)).whenComplete((n,e)->r.server().execute(()->feedback(s,e==null&&n==1?"Жалоба закрыта":"Не удалось закрыть жалобу")));r.audit(s.getName(),"report-resolve",Integer.toString(id));return 1;})));
   root.then(literal("diag").executes(c->{feedback(c.getSource(),"Готовность: "+r.ready()+", средний тик: "+r.server().getAverageTickTime()+" мс");r.audit(c.getSource().getName(),"diagnostics","server");return 1;}));
   root.then(literal("role").requires(this::owner).then(argument("uuid",StringArgumentType.word()).then(argument("role",StringArgumentType.word()).executes(c->{var s=c.getSource();try{UUID id=UUID.fromString(StringArgumentType.getString(c,"uuid"));Role role=Role.valueOf(StringArgumentType.getString(c,"role").toUpperCase(Locale.ROOT));if(!begin(s))return 0;State before=copy();state.roles.put(id.toString(),role);persist(s,before,"staff-role",id+" "+role,()->feedback(s,"Роль назначена: "+role));}catch(IllegalArgumentException e){feedback(s,"Укажите UUID и MODERATOR, CURATOR или TECHADMIN");}return 1;}))));
   root.then(literal("revoke").requires(this::owner).then(argument("uuid",StringArgumentType.word()).executes(c->{var s=c.getSource();try{UUID id=UUID.fromString(StringArgumentType.getString(c,"uuid"));if(!begin(s))return 0;State before=copy();state.roles.remove(id.toString());persist(s,before,"staff-revoke",id.toString(),()->feedback(s,"Роль снята"));}catch(IllegalArgumentException e){feedback(s,"Некорректный UUID");}return 1;})));
   for(String kind:List.of("ban","mute"))root.then(literal(kind).requires(this::canModerate).then(argument("player",StringArgumentType.word()).then(argument("minutes",IntegerArgumentType.integer(0,525600)).then(argument("reason",StringArgumentType.greedyString()).executes(c->{punish(c.getSource(),StringArgumentType.getString(c,"player"),kind,IntegerArgumentType.getInteger(c,"minutes"),StringArgumentType.getString(c,"reason"));return 1;})))));
   root.then(literal("kick").requires(this::canModerate).then(argument("player",StringArgumentType.word()).then(argument("reason",StringArgumentType.greedyString()).executes(c->{var s=c.getSource();var p=r.server().getPlayerManager().getPlayer(StringArgumentType.getString(c,"player"));String reason=StringArgumentType.getString(c,"reason");if(p==null||reason.length()>300||protectedTarget(s,p.getUuid())){feedback(s,"Цель недоступна или причина слишком длинная");return 0;}r.audit(s.getName(),"kick",p.getUuid()+" "+reason);p.networkHandler.disconnect(Text.literal(reason));feedback(s,"Игрок отключён");return 1;}))));
   for(String kind:List.of("unban","unmute"))root.then(literal(kind).requires(this::canModerate).then(argument("player",StringArgumentType.word()).then(argument("reason",StringArgumentType.greedyString()).executes(c->{var s=c.getSource();UUID id=target(StringArgumentType.getString(c,"player"));String reason=StringArgumentType.getString(c,"reason");if(id==null||reason.length()>300||protectedTarget(s,id)){feedback(s,"Укажите онлайн-имя или UUID и причину до 300 символов");return 0;}if(!begin(s))return 0;State before=copy();(kind.equals("unban")?state.bans:state.mutes).remove(id.toString());persist(s,before,kind,id+" "+reason,()->feedback(s,"Наказание снято"));return 1;}))));
   d.register(root);
  });
 }
 private boolean canChat(ServerPlayerEntity p){if(!loaded)return false;Sanction mute=state.mutes.get(p.getUuid().toString());if(mute!=null&&mute.active()){r.reply(p,"Чат ограничен: "+mute.reason);return false;}return true;}
 private UUID target(String arg){var online=r.server().getPlayerManager().getPlayer(arg);if(online!=null)return online.getUuid();try{return UUID.fromString(arg);}catch(IllegalArgumentException e){return null;}}
 private boolean protectedTarget(ServerCommandSource s,UUID id){if(owner(s))return false;if(state.roles.containsKey(id.toString()))return true;var p=r.server().getPlayerManager().getPlayer(id);return p!=null&&owner(p.getCommandSource());}
 private void punish(ServerCommandSource s,String name,String kind,int minutes,String reason){UUID id=target(name);if(id==null||reason.length()>300||reason.isBlank()||protectedTarget(s,id)){feedback(s,"Укажите онлайн-имя или UUID, допустимую цель и причину до 300 символов");return;}Role actor=s.getEntity() instanceof ServerPlayerEntity p?state.roles.get(p.getUuid().toString()):null;if(!owner(s)&&actor!=Role.CURATOR&&(minutes==0||minutes>1440)){feedback(s,"Модератор может назначать наказания до 24 часов");return;}if(!begin(s))return;State before=copy();Sanction sanction=new Sanction(kind,reason,minutes==0?0:System.currentTimeMillis()+minutes*60000L,s.getName());(kind.equals("ban")?state.bans:state.mutes).put(id.toString(),sanction);persist(s,before,kind,id+" "+minutes+"m "+reason,()->{var p=r.server().getPlayerManager().getPlayer(id);if(p!=null){if(kind.equals("ban"))p.networkHandler.disconnect(Text.literal("Блокировка: "+reason));else r.reply(p,"Вы получили мут: "+reason);}feedback(s,"Наказание сохранено");});}
 private boolean begin(ServerCommandSource s){if(!loaded||busy){feedback(s,"Дождитесь сохранения предыдущей операции");return false;}busy=true;return true;}
 private State copy(){return gson.fromJson(gson.toJson(state),State.class);}
 private void persist(ServerCommandSource s,State before,String action,String detail,Runnable done){save().whenComplete((v,e)->r.server().execute(()->{busy=false;if(e!=null){state=before;feedback(s,"Не удалось сохранить изменение; оно отменено");return;}r.audit(s.getName(),action,detail);done.run();}));}
 private void reports(ServerCommandSource source){r.store.submit(c->{List<String> out=new ArrayList<>();try(var p=c.prepareStatement("SELECT id,target,body FROM reports WHERE status='OPEN' ORDER BY id LIMIT 20");var rs=p.executeQuery()){while(rs.next())out.add("#"+rs.getInt(1)+" • "+rs.getString(2)+": "+rs.getString(3));}return out;}).whenComplete((lines,e)->r.server().execute(()->{if(e!=null){feedback(source,"Ошибка чтения жалоб");return;}if(lines.isEmpty())feedback(source,"Открытых жалоб нет");else lines.forEach(t->feedback(source,t));}));r.audit(source.getName(),"reports-read","queue");}
 private void menu(ServerCommandSource s){if(s.getEntity() instanceof ServerPlayerEntity p){List<MenuEntry> entries=new ArrayList<>();entries.add(new MenuEntry(Menus.icon(Items.COMPARATOR,"Диагностика","Состояние и нагрузка"),()->feedback(s,"Средний тик: "+r.server().getAverageTickTime()+" мс")));if(canModerate(s)){entries.add(new MenuEntry(Menus.icon(Items.PAPER,"Жалобы","Список открытых обращений"),()->reports(s)));entries.add(new MenuEntry(Menus.icon(Items.BOOK,"Наказания","/staff kick <игрок> <причина>","/staff ban|mute <игрок> <минуты> <причина>","0 минут — навсегда, только куратор"),()->feedback(s,"Для снятия: /staff unban|unmute <игрок/UUID> <причина>")));}r.menu(p,"Персонал",entries);}else feedback(s,"/staff reports | diag | role <uuid> <роль> | ban | mute | kick | unban | unmute");}
}
