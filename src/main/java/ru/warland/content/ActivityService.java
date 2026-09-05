package ru.warland.content;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.warland.api.WarLandApi;

/** Server-observed daily exploration and a weekly timed city route; no item or currency faucet. */
final class ActivityService {
    private final WarLandApi api; private final ContentStore store; private final MinecraftServer server;
    private final Map<UUID,Sample> samples=new HashMap<>();
    private final Clock clock;
    private record Sample(String world,double x,double y,double z,long time) {}
    private record Observation(UUID id,String world,double x,double y,double z,double movement,long chunk) {}
    ActivityService(WarLandApi api,ContentStore store,MinecraftServer server) { this(api,store,server,Clock.systemUTC()); }
    ActivityService(WarLandApi api,ContentStore store,MinecraftServer server,Clock clock) { this.api=api;this.store=store;this.server=server;this.clock=clock; }
    void tick(long tick) {
        if(tick%20!=0 || !store.ready()) return;
        Instant now=clock.instant(); List<Observation> observations=new ArrayList<>();
        for(ServerPlayerEntity player:server.getPlayerManager().getPlayerList()) {
            String world=player.getEntityWorld().getRegistryKey().getValue().toString();
            Sample next=new Sample(world,player.getX(),player.getY(),player.getZ(),now.toEpochMilli());
            Sample previous=samples.put(player.getUuid(),next);
            if(previous==null || !world.equals(previous.world) || now.toEpochMilli()-previous.time>2500
                    || now.toEpochMilli()-previous.time<500 || api.inCombat(player.getUuid())) continue;
            double dx=next.x-previous.x,dz=next.z-previous.z;
            double distance=ActivityRules.acceptedMovement(Math.sqrt(dx*dx+dz*dz),player.isOnGround(),player.isCreative(),player.isSpectator(),player.hasVehicle());
            if(distance<=0 || Math.abs(next.y-previous.y)>4 || player.getAbilities().flying) continue;
            long chunk=player.getChunkPos().toLong();
            observations.add(new Observation(player.getUuid(),world,next.x,next.y,next.z,distance,chunk));
        }
        if(!store.idle() || observations.isEmpty()) return;
        store.change(data->{
            for(Observation o:observations) {
                if(!data.profiles.containsKey(o.id.toString()) && data.profiles.size()>=10_000) continue;
                var p=data.profile(o.id);ActivityRules.roll(p,now);
                if(o.world.equals("minecraft:overworld")) {
                    p.walked=Math.min(1000,p.walked+o.movement);
                    if(p.survey.size()<6)p.survey.add(o.chunk);
                }
                if(o.world.equals("warland:capital") && Math.abs(o.y-(Layouts.CAPITAL_Y+1))<4) {
                    for(var zone:Layouts.ZONES) if(distanceSquared(o.x,o.z,zone.x()+0.5,zone.z()+0.5)<=36) p.zones.add(zone.id());
                    if(p.eventStarted>0 && p.eventWeek==ActivityRules.week(now) && !p.eventClaimed && ActivityRules.nextOrCurrentEvent(now).active(now)) {
                        p.eventWalked=Math.min(5000,p.eventWalked+o.movement);
                        if(p.checkpoint<Layouts.CIRCUIT.size() && now.toEpochMilli()-p.eventLastCheckpoint>=8000) {
                            var target=Layouts.CIRCUIT.get(p.checkpoint);
                            if(distanceSquared(o.x,o.z,target.x()+0.5,target.z()+0.5)<=25) {p.checkpoint++;p.eventLastCheckpoint=now.toEpochMilli();}
                        }
                    }
                }
            }
        },()->{});
    }
    private static double distanceSquared(double x,double z,double a,double b) {return (x-a)*(x-a)+(z-b)*(z-b);}
    private void withProfile(ServerPlayerEntity player,Runnable action) {
        if(!store.ready() || !api.ready()) {api.reply(player,"Задания временно недоступны.");return;}
        if(!store.data().profiles.containsKey(player.getUuidAsString()) && store.data().profiles.size()>=10_000) {api.reply(player,"Лимит профилей достигнут. Обратитесь к персоналу.");return;}
        store.change(s->ActivityRules.roll(s.profile(player.getUuid()),clock.instant()),action,error->api.reply(player,"Профиль не сохранён. Награды не выдавались."));
    }
    void quests(ServerPlayerEntity player) {withProfile(player,()->questMenu(player));}
    private void questMenu(ServerPlayerEntity player) {
        var p=store.data().profiles.get(player.getUuidAsString());
        api.menu(player,"Ежедневные задания · МСК",List.of(
                ContentFeature.entry(Items.COMPASS,"Столица: "+p.zones.size()+"/4 квартала · +12 реп.",()->claim(player,"tour")),
                ContentFeature.entry(Items.LEATHER_BOOTS,"Марш: "+(int)p.walked+"/600 м · +16 реп.",()->claim(player,"walk")),
                ContentFeature.entry(Items.MAP,"Разведка: "+p.survey.size()+"/6 чанков · +18 реп.",()->claim(player,"survey")),
                ContentFeature.entry(Items.BOOK,"Условия и недельный потолок",()->api.reply(player,"Нажмите выполненное задание для награды. Столица: посетить 4 цветных терминала пешком. Марш и разведка: обычное движение по земле в Верхнем мире. Полёт, техника, творческий режим, бой и резкие телепорты не засчитываются. Обновление в 00:00 МСК; репутация за неделю ограничена. Денежных наград нет.")),
                ContentFeature.entry(Items.GOLD_INGOT,"Репутация "+p.reputation+" · "+p.weekReputation+"/"+store.data().config.weeklyReputationCap+" за неделю",()->reputation(player))));
    }
    private void claim(ServerPlayerEntity player,String quest) {
        if(player.isCreative() || player.isSpectator() || api.inCombat(player.getUuid())) {api.reply(player,"Награда доступна в выживании и вне боя.");return;}
        final ActivityRules.Claim[] result={ActivityRules.Claim.INCOMPLETE};
        store.change(data->{var p=data.profile(player.getUuid());ActivityRules.roll(p,clock.instant());result[0]=ActivityRules.claim(p,quest,data.config.weeklyReputationCap);},()->{
            api.reply(player,claimMessage(result[0]));if(result[0]==ActivityRules.Claim.GRANTED)api.audit(player.getUuidAsString(),"quest.claim",quest+":"+ActivityRules.day(clock.instant()));questMenu(player);
        },error->api.reply(player,"Награда не подтверждена. Повтор безопасен после восстановления хранилища."));
    }
    void reputation(ServerPlayerEntity player) {withProfile(player,()->{
        var p=store.data().profiles.get(player.getUuidAsString());
        api.menu(player,"Репутация столицы",List.of(
                ContentFeature.entry(Items.GOLD_INGOT,ActivityRules.rank(p.reputation)+" · "+p.reputation+" репутации",()->api.reply(player,"Титулы: Горожанин — 80, Мастер — 200, Старожил — 500. Мастерская открывается при 40 репутации. Боевых усилений и денежного дохода репутация не даёт.")),
                ContentFeature.entry(Items.CLOCK,"Недельный предел: "+p.weekReputation+"/"+store.data().config.weeklyReputationCap,()->quests(player)),
                ContentFeature.entry(Items.SMITHING_TABLE,"Чертёж мастерской · 40 репутации",()->api.reply(player,"Чертежи: /citybuild. Репутация открывает выбор, но не заменяет оплату и материалы."))));
    });}
    void events(ServerPlayerEntity player) {withProfile(player,()->{
        Instant now=clock.instant();var window=ActivityRules.nextOrCurrentEvent(now);var p=store.data().profiles.get(player.getUuidAsString());
        String when=DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ActivityRules.ZONE).format(window.start());
        String progress="Маршрут: "+p.checkpoint+"/8 · "+(int)p.eventWalked+"/200 м";
        List<WarLandApi.MenuEntry> entries=new ArrayList<>();
        entries.add(ContentFeature.entry(Items.CLOCK,(window.active(now)?"Событие идёт до 19:00 МСК":"Субботний маршрут · "+when+" МСК"),()->api.reply(player,"Каждую субботу 18:00–19:00 МСК: пройти 8 точек столицы по порядку, не менее 200 м и 90 секунд. Награда +25 репутации один раз в неделю, внутри общего лимита. Нет ставок, добычи и PvP.")));
        entries.add(ContentFeature.entry(Items.LIME_DYE,"Начать у центрального появления",()->startEvent(player)));
        entries.add(ContentFeature.entry(Items.COMPASS,progress+" · следующая точка",()->{
            if(p.checkpoint<Layouts.CIRCUIT.size()) {var target=Layouts.CIRCUIT.get(p.checkpoint);api.reply(player,"Точка "+(p.checkpoint+1)+": X "+target.x()+", Z "+target.z()+". Между точками минимум 8 секунд.");}
            else api.reply(player,"Все точки пройдены. Проверьте 200 м и 90 секунд, затем заберите награду до 19:00 МСК.");
        }));
        entries.add(ContentFeature.entry(Items.GOLD_INGOT,"Забрать награду за маршрут",()->claimEvent(player)));
        api.menu(player,"События · городской маршрут",entries);
    });}
    private void startEvent(ServerPlayerEntity player) {
        Instant now=clock.instant();
        if(!ActivityRules.nextOrCurrentEvent(now).active(now) || !player.getEntityWorld().getRegistryKey().equals(CapitalService.WORLD)
                || player.squaredDistanceTo(0.5,Layouts.CAPITAL_Y+1,12.5)>144 || player.isCreative() || player.isSpectator() || player.hasVehicle() || api.inCombat(player.getUuid())) {
            api.reply(player,"Старт только в субботу 18:00–19:00 МСК, у появления в столице, в выживании и вне боя.");return;
        }
        store.change(s->{var p=s.profile(player.getUuid());ActivityRules.roll(p,now);if(p.eventStarted==0 && !p.eventClaimed){p.eventStarted=now.toEpochMilli();p.eventLastCheckpoint=p.eventStarted;p.checkpoint=0;p.eventWalked=0;}},()->api.reply(player,"Маршрут активен: /events → следующая точка. Повторное нажатие не сбрасывает прогресс."));
    }
    private void claimEvent(ServerPlayerEntity player) {
        if(player.isCreative() || player.isSpectator() || api.inCombat(player.getUuid()))return;
        final ActivityRules.Claim[] result={ActivityRules.Claim.INCOMPLETE};
        store.change(s->{var p=s.profile(player.getUuid());ActivityRules.roll(p,clock.instant());result[0]=ActivityRules.claimEvent(p,clock.instant(),s.config.weeklyReputationCap);},()->{
            api.reply(player,claimMessage(result[0]));if(result[0]==ActivityRules.Claim.GRANTED)api.audit(player.getUuidAsString(),"event.claim","city-circuit:"+ActivityRules.week(clock.instant()));
        });
    }
    private static String claimMessage(ActivityRules.Claim claim) {return switch(claim){case GRANTED->"Репутация получена и сохранена.";case ALREADY->"Награда уже получена: повтор не начисляет репутацию.";case INCOMPLETE->"Условия ещё не выполнены или окно события закрыто.";case WEEKLY_CAP->"Эта награда превысит недельный предел репутации.";};}
    void tutorial(ServerPlayerEntity player) {
        api.menu(player,"Обучение · короткий маршрут",List.of(
                ContentFeature.entry(Items.EMERALD,"1. Торговля",()->api.reply(player,"Бирюзовый квартал: /shop и /ah. Сначала проверьте цены, лимиты и комиссии. Скупленные строительные блоки не создаются бесплатно.")),
                ContentFeature.entry(Items.BOOK,"2. Задания и репутация",()->quests(player)),
                ContentFeature.entry(Items.STONE_BRICKS,"3. Территория и чертежи",()->api.reply(player,"Создайте или найдите государство через /nation. Уточните права на чанк через /claim. /citybuild проверит каждый блок и не очистит чужую постройку.")),
                ContentFeature.entry(Items.TARGET,"4. Полигон",()->api.reply(player,"Оранжевый квартал: три ванильные мишени и полоса препятствий. Столица — безопасная зона. Боевые механики и техника подключаются отдельным модулем; муляжи не выдают наград.")),
                ContentFeature.entry(Items.PAPER,"Пропустить · повтор доступен всегда",()->withProfile(player,()->store.change(s->s.profile(player.getUuid()).tutorialSkipped=true,()->api.reply(player,"Обучение пропущено. Вернуться можно через /capital. Стартовые деньги и набор здесь не выдаются."))))));
    }
    void disconnect(UUID id) {samples.remove(id);}
    void stop() {samples.clear();}
}
