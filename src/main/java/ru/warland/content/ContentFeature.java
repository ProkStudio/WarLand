package ru.warland.content;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.warland.api.Feature;
import ru.warland.api.WarLandApi;

/** Server-only content entrypoint. Core owns lifecycle, menus, money, claims and teleport policy. */
public final class ContentFeature implements Feature {
    private static final Logger LOG=LoggerFactory.getLogger("warland-content");
    private WarLandApi api;
    private ContentStore store;
    private CapitalService capital;
    private BuildingService buildings;
    private ActivityService activities;
    private long ticks;
    private boolean started;
    private final Map<UUID,Long> menuCooldown=new HashMap<>();

    @Override public void initialize(WarLandApi api) {
        this.api=Objects.requireNonNull(api);
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)-> {
            dispatcher.register(CommandManager.literal("capital").executes(c->player(c.getSource(),this::capitalMenu))
                    .then(CommandManager.literal("status").executes(c->{c.getSource().sendFeedback(()->Text.literal(capital==null?"Столица ещё не загружена.":capital.status()),false);return 1;}))
                    .then(CommandManager.literal("resume").requires(this::admin).then(CommandManager.argument("reason",StringArgumentType.greedyString()).executes(c->{
                        String reason=StringArgumentType.getString(c,"reason");if(ready(c.getSource()) && validReason(c.getSource(),reason))capital.resume(c.getSource().getName(),reason);return 1;
                    })))
                    .then(CommandManager.literal("budget").requires(this::admin).then(CommandManager.argument("blocks",IntegerArgumentType.integer(8,256)).executes(c->{
                        if(!ready(c.getSource()))return 0;int value=IntegerArgumentType.getInteger(c,"blocks");
                        store.change(s->s.config.placementBudget=value,()->{api.audit(c.getSource().getName(),"content.budget",Integer.toString(value));c.getSource().sendFeedback(()->Text.literal("Лимит размещения сохранён: "+value+" блоков за пакет."),false);});return 1;
                    }))));
            dispatcher.register(CommandManager.literal("citybuild").executes(c->player(c.getSource(),p->{if(uiReady(p))buildings.menu(p);}))
                    .then(CommandManager.literal("purchases").requires(this::admin).then(CommandManager.argument("enabled",BoolArgumentType.bool()).executes(c->{
                        if(!ready(c.getSource()))return 0;boolean enabled=BoolArgumentType.getBool(c,"enabled");
                        store.change(s->s.config.buildingPurchasesEnabled=enabled,()->{api.audit(c.getSource().getName(),"content.purchase_gate",Boolean.toString(enabled));c.getSource().sendFeedback(()->Text.literal(enabled?"Покупки включены. Персонал принимает ограничения неатомарного escrow и обязан провести испытания восстановления.":"Новые покупки отключены. Начатые работы не удалены."),false);});return 1;
                    })))
                    .then(CommandManager.literal("recover").requires(this::admin).then(CommandManager.argument("job",StringArgumentType.word()).then(CommandManager.argument("reason",StringArgumentType.greedyString()).executes(c->{
                        String reason=StringArgumentType.getString(c,"reason");if(!ready(c.getSource()) || !validReason(c.getSource(),reason))return 0;
                        String id=StringArgumentType.getString(c,"job");
                        if(!store.data().jobs.containsKey(id)){c.getSource().sendError(Text.literal("Работа не найдена."));return 0;}
                        buildings.recover(id,c.getSource().getName(),reason);c.getSource().sendFeedback(()->Text.literal("Запрошена сверка. Возобновление допустимо только после ручной проверки инвентаря, блоков и платежа. Проверьте состояние работы."),false);return 1;
                    })))));
            dispatcher.register(CommandManager.literal("quests").executes(c->player(c.getSource(),p->{if(uiReady(p))activities.quests(p);})));
            dispatcher.register(CommandManager.literal("rep").executes(c->player(c.getSource(),p->{if(uiReady(p))activities.reputation(p);})));
            dispatcher.register(CommandManager.literal("events").executes(c->player(c.getSource(),p->{if(uiReady(p))activities.events(p);})));
        });
        PlayerBlockBreakEvents.BEFORE.register((world,player,pos,state,entity)->!protectedCell(world,pos));
        AttackBlockCallback.EVENT.register((player,world,hand,pos,direction)->protectedCell(world,pos)?ActionResult.FAIL:ActionResult.PASS);
        // Read-only service dispatch precedes the core safe-zone veto, regardless of initialization order.
        Identifier terminalPhase=Identifier.of("warland","content_terminals");
        UseBlockCallback.EVENT.addPhaseOrdering(terminalPhase,Event.DEFAULT_PHASE);
        UseBlockCallback.EVENT.register(terminalPhase,(player,world,hand,hit)-> {
            if(world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer))return ActionResult.PASS;
            if(world.getRegistryKey().equals(CapitalService.WORLD)) {
                if(hand==Hand.MAIN_HAND && uiReady(serverPlayer)) {
                    for(var zone:Layouts.ZONES) if(hit.getBlockPos().getX()==zone.x() && hit.getBlockPos().getZ()==zone.z()
                            && hit.getBlockPos().getY()>=Layouts.CAPITAL_Y+1 && hit.getBlockPos().getY()<=Layouts.CAPITAL_Y+3) {
                        long now=System.currentTimeMillis();if(now-menuCooldown.getOrDefault(serverPlayer.getUuid(),0L)>=750){menuCooldown.put(serverPlayer.getUuid(),now);service(serverPlayer,zone.id());}
                        return ActionResult.SUCCESS;
                    }
                }
                return ActionResult.FAIL;
            }
            return protectedCell(world,hit.getBlockPos()) || protectedCell(world,hit.getBlockPos().offset(hit.getSide()))?ActionResult.FAIL:ActionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,source,amount)->!entity.getEntityWorld().getRegistryKey().equals(CapitalService.WORLD));
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{
            UUID id=handler.player.getUuid();menuCooldown.remove(id);
            if(buildings!=null)buildings.disconnect(id);if(activities!=null)activities.disconnect(id);
        });
    }
    @Override public void serverStarted(MinecraftServer server) {
        ticks=0;started=false;
        store=new ContentStore(api,server);
        capital=new CapitalService(api,store,server);
        buildings=new BuildingService(api,store,server);
        activities=new ActivityService(api,store,server);
        store.load(()->{started=true;capital.start();LOG.info("Content state loaded. Building purchases enabled={}",store.data().config.buildingPurchasesEnabled);});
    }
    @Override public void tick(MinecraftServer server) {
        if(!started || !store.ready() || !api.ready())return;
        ticks++;
        try { capital.tick();buildings.tick(ticks);activities.tick(ticks); }
        catch(RuntimeException failure) {
            LOG.error("Content suspended after a runtime error; recovery required",failure);
            started=false;capital.stop();store.close();return;
        }
        if(ticks%20==0) {
            capital.updateLoadedSigns();
            if(capital.isReady())for(var p:server.getPlayerManager().getPlayerList())if(p.getEntityWorld().getRegistryKey().equals(CapitalService.WORLD) && p.getY()<Layouts.CAPITAL_Y-8) {
                p.requestTeleportAndDismount(CapitalService.ARRIVAL.getX()+0.5,CapitalService.ARRIVAL.getY(),CapitalService.ARRIVAL.getZ()+0.5);
                p.fallDistance=0;
            }
        }
    }
    @Override public void stopped() {
        started=false;menuCooldown.clear();
        if(capital!=null)capital.stop();if(buildings!=null)buildings.stop();if(activities!=null)activities.stop();if(store!=null)store.close();
    }
    private boolean protectedCell(World world,BlockPos pos) {
        return world.getRegistryKey().equals(CapitalService.WORLD) || (buildings!=null && buildings.reserved(world,pos));
    }
    private boolean admin(ServerCommandSource source) { return api.staff(source,"warland.content.admin"); }
    private boolean ready(ServerCommandSource source) {if(!started || store==null || !store.ready() || !api.ready()){source.sendError(Text.literal("Контент ещё не готов или хранилище недоступно."));return false;}return true;}
    private boolean uiReady(ServerPlayerEntity p) {if(!started || store==null || !store.ready() || !api.ready()){api.reply(p,"Контент пока недоступен. Мир и деньги не изменены.");return false;}return true;}
    private static boolean validReason(ServerCommandSource source,String reason) {if(!PlacementRules.validReason(reason)){source.sendError(Text.literal("Причина: 8–160 символов, без управляющих знаков."));return false;}return true;}
    private static int player(ServerCommandSource source,Consumer<ServerPlayerEntity> action) {ServerPlayerEntity p=source.getPlayer();if(p==null){source.sendError(Text.literal("Эта команда доступна игроку."));return 0;}action.accept(p);return 1;}
    static WarLandApi.MenuEntry entry(Item item,String label,Runnable action) {ItemStack icon=new ItemStack(item);icon.set(DataComponentTypes.CUSTOM_NAME,Text.literal(label));return new WarLandApi.MenuEntry(icon,action);}
    private void capitalMenu(ServerPlayerEntity p) {
        if(!uiReady(p))return;
        List<WarLandApi.MenuEntry> entries=new ArrayList<>();
        entries.add(entry(Items.COMPASS,capital.isReady()?"В столицу · правила /spawn":"Столица строится/проверяется",()->{if(capital.isReady())coreCommand(p,"spawn");else api.reply(p,capital.status());}));
        entries.add(entry(Items.EMERALD,"Торговый квартал",()->service(p,"market")));
        entries.add(entry(Items.BOOK,"Задания и репутация",()->service(p,"quests")));
        entries.add(entry(Items.MINECART,"Транспорт",()->service(p,"transport")));
        entries.add(entry(Items.TARGET,"Обучение",()->service(p,"training")));
        entries.add(entry(Items.STONE_BRICKS,"Городские чертежи",()->buildings.menu(p)));
        entries.add(entry(Items.CLOCK,"Недельное событие",()->activities.events(p)));
        api.menu(p,"WarLand · столица",entries);
    }
    private void service(ServerPlayerEntity p,String id) {
        switch(id) {
            case "market" -> api.menu(p,"Торговый квартал",List.of(entry(Items.EMERALD,"Магазин и скупщик",()->coreCommand(p,"shop")),entry(Items.CHEST,"Глобальный аукцион",()->coreCommand(p,"ah")),entry(Items.WHITE_BANNER,"Государство",()->coreCommand(p,"nation"))));
            case "quests" -> api.menu(p,"Штаб заданий",List.of(entry(Items.BOOK,"Ежедневные задания",()->activities.quests(p)),entry(Items.GOLD_INGOT,"Репутация",()->activities.reputation(p)),entry(Items.CLOCK,"Субботний маршрут",()->activities.events(p))));
            case "transport" -> {
                List<WarLandApi.MenuEntry> entries=new ArrayList<>();
                for(var zone:Layouts.ZONES)entries.add(entry(Items.COMPASS,zone.name(),()->{if(capital.isReady())coreCommand(p,"warp capital_"+zone.id());else api.reply(p,"Варпы появятся после проверки столицы.");}));
                entries.add(entry(Items.BOOK,"Задержки, бой, цена и отмена — правила ядра",()->api.reply(p,"Терминал не обходит ограничения /warp. При недоступной команде перемещения нет.")));
                api.menu(p,"Транспортный терминал",entries);
            }
            default -> activities.tutorial(p);
        }
    }
    private void coreCommand(ServerPlayerEntity player,String command) {
        String root=command.split(" ",2)[0];
        if(api.server().getCommandManager().getDispatcher().getRoot().getChild(root)==null) {api.reply(player,"Сервис /"+root+" пока не подключён ядром.");return;}
        api.server().getCommandManager().parseAndExecute(player.getCommandSource(),command);
    }
}
