package ru.warland.content;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import ru.warland.api.WarLandApi;

/** Paid blueprints with all-air preflight, a write-ahead batch journal, and explicit crash quarantine. */
final class BuildingService {
    private final WarLandApi api;
    private final MinecraftServer server;
    private final ContentStore store;
    private final Map<String,BlockPlan> plans=Layouts.buildings();
    private final Map<UUID,Preview> previews=new HashMap<>();
    private final Set<String> busy=new HashSet<>();
    private final Set<UUID> awaitingBalance=new HashSet<>();
    private int roundRobin;
    private static final class Preview {
        final UUID owner; final String token=UUID.randomUUID().toString(); final RegistryKey<World> world;
        final BlockPlan plan; final BlockPos origin; final long expires=System.currentTimeMillis()+60_000;
        boolean validating; int cursor;
        Preview(ServerPlayerEntity player,BlockPlan plan,BlockPos origin) { owner=player.getUuid();world=player.getEntityWorld().getRegistryKey();this.plan=plan;this.origin=origin; }
    }
    BuildingService(WarLandApi api,ContentStore store,MinecraftServer server) { this.api=api;this.store=store;this.server=server; }
    void menu(ServerPlayerEntity player) {
        if(!available(player)) return;
        List<WarLandApi.MenuEntry> entries=new ArrayList<>();
        for(BlockPlan plan:plans.values()) entries.add(ContentFeature.entry(plan.id().equals("depot_v1")?Items.BARREL:Items.SMITHING_TABLE,
                PlacementRules.name(plan.id())+" · "+store.data().config.price(plan.id())+" монет + все материалы",()->preview(player,plan)));
        for(var job:store.data().jobs.values()) if(job.owner.equals(player.getUuidAsString())) entries.add(ContentFeature.entry(Items.PAPER,
                PlacementRules.name(job.plan)+" · "+job.stage+" · "+job.cursor+" блоков",()->jobMenu(player,job.id)));
        entries.add(ContentFeature.entry(Items.BOOK,"Правила: площадка пуста, вход на север, без автоматического возврата",()->api.reply(player,
                "Стройка только в Верхнем мире. Вход — на север (−Z). Все материалы расходуются по точной ведомости блоков. Готовые здания дают только ванильные рабочие места и хранилища; пассивного дохода нет. Перезапуск требует сверки незавершённых работ.")));
        if(!store.data().config.buildingPurchasesEnabled) entries.add(ContentFeature.entry(Items.BARRIER,"Покупки отключены до испытаний escrow",()->api.reply(player,"Предпросмотр доступен. Реальная покупка включается персоналом только на испытательном сервере после проверки восстановления.")));
        api.menu(player,"Городские чертежи",entries);
    }
    private boolean available(ServerPlayerEntity p) { if(!store.ready() || !api.ready()) { api.reply(p,"Модуль строительства пока недоступен.");return false;}return true; }
    private boolean eligible(ServerPlayerEntity p) {
        return !p.isCreative() && !p.isSpectator() && !p.hasVehicle() && !api.inCombat(p.getUuid())
                && p.getEntityWorld().getRegistryKey().equals(World.OVERWORLD);
    }
    private void preview(ServerPlayerEntity player,BlockPlan plan) {
        if(!eligible(player)) { api.reply(player,"Нужны выживание, Верхний мир и отсутствие боя/техники.");return; }
        Direction facing=player.getHorizontalFacing();
        BlockPos origin=player.getBlockPos().add(facing.getOffsetX()*9-plan.width()/2,0,facing.getOffsetZ()*9-plan.depth()/2);
        Preview preview=new Preview(player,plan,origin);previews.put(player.getUuid(),preview);
        String bill=plan.materials().entrySet().stream().map(e->label(e.getKey())+" ×"+e.getValue()).reduce((a,b)->a+", "+b).orElse("");
        api.reply(player,"Площадка: "+origin.toShortString()+"; "+plan.width()+"×"+plan.depth()+"×"+plan.height()+". Вход −Z. Материалы: "+bill);
        api.menu(player,"Предпросмотр · "+PlacementRules.name(plan.id()),List.of(
                ContentFeature.entry(Items.LIME_DYE,"Проверить и оплатить: "+store.data().config.price(plan.id())+" + материалы",()->confirm(player,preview.token)),
                ContentFeature.entry(Items.PAPER,"Точная ведомость материалов",()->api.reply(player,bill)),
                ContentFeature.entry(Items.BARRIER,"Отмена: ничего не списано",()->{previews.remove(player.getUuid());api.reply(player,"Предпросмотр отменён.");})));
        particles(player,preview);
    }
    private void confirm(ServerPlayerEntity player,String token) {
        Preview p=previews.get(player.getUuid());
        if(p==null || !p.token.equals(token) || p.expires<System.currentTimeMillis() || !eligible(player)) { api.reply(player,"Предпросмотр устарел. Выберите чертёж заново.");return; }
        if(!store.data().config.buildingPurchasesEnabled) { api.reply(player,"Покупки закрыты до интеграционных испытаний. Предпросмотр ничего не списывает.");return; }
        if(p.validating || awaitingBalance.contains(player.getUuid())) return;
        if(!store.idle()) { api.reply(player,"Хранилище занято. Повторите подтверждение через секунду.");return; }
        if(!limits(player,p)) return;
        if(!hasMaterials(player,p.plan)) { api.reply(player,"Не хватает обычных, непереименованных материалов из ведомости.");return; }
        if(p.plan.id().equals("workshop_v1") && reputation(player)<40) { api.reply(player,"Мастерская требует 40 репутации.");return; }
        p.validating=true;p.cursor=0;api.reply(player,"Проверяю весь свободный объём и основание. Пока ничего не списано.");
    }
    private int reputation(ServerPlayerEntity player) { var p=store.data().profiles.get(player.getUuidAsString());return p==null?0:p.reputation; }
    private boolean limits(ServerPlayerEntity player,Preview preview) {
        var d=store.data();
        if(d.jobs.size()>=d.config.maxJobs || PlacementRules.ownerCapReached(d,player.getUuidAsString())
                || d.jobs.values().stream().filter(j->!j.terminal()).count()>=d.config.maxActiveJobs
                || d.jobs.values().stream().anyMatch(j->!j.terminal() && j.owner.equals(player.getUuidAsString()))) {
            api.reply(player,"Лимит зданий или активных работ достигнут.");return false;
        }
        var bounds=PlacementRules.bounds(preview.plan,preview.origin.getX(),preview.origin.getY(),preview.origin.getZ());
        for(var job:d.jobs.values()) if(job.world.equals(preview.world.getValue().toString()) && !job.stage.equals("ABANDONED")
                && bounds.intersects(PlacementRules.bounds(plans.get(job.plan),job.x,job.y,job.z))) {
            api.reply(player,"Площадка пересекает другое зарегистрированное здание.");return false;
        }
        return true;
    }
    void tick(long tick) {
        if(!store.ready()) return;
        for(Preview preview:List.copyOf(previews.values())) {
            ServerPlayerEntity p=server.getPlayerManager().getPlayer(preview.owner);
            if(p==null || preview.expires<System.currentTimeMillis() || !p.getEntityWorld().getRegistryKey().equals(preview.world)) { previews.remove(preview.owner);continue; }
            if(tick%20==0) particles(p,preview);
        }
        if(!store.idle()) return;
        for(Preview preview:List.copyOf(previews.values())) if(preview.validating) { validate(preview);return; }
        List<ContentData.Job> active=store.data().jobs.values().stream().filter(j->j.stage.equals("PLACING") && !busy.contains(j.id)).toList();
        if(active.isEmpty()) return;
        for(int n=0;n<active.size();n++) {
            var job=active.get(Math.floorMod(roundRobin++,active.size()));
            ServerPlayerEntity owner=server.getPlayerManager().getPlayer(UUID.fromString(job.owner));
            if(owner==null || !eligible(owner) || !owner.getEntityWorld().getRegistryKey().getValue().toString().equals(job.world)
                    || owner.squaredDistanceTo(job.x,job.y,job.z)>64*64) continue;
            BlockPlan plan=plans.get(job.plan);
            if(!plan.fingerprint().equals(job.hash) || job.cursor>plan.cells().size()) { pause(job.id,"Чертёж не совпадает с сохранённой версией.");return; }
            if(job.cursor==plan.cells().size()) { finish(job.id);return; }
            BlockPos first=at(job,plan.cells().get(job.cursor()));
            if(!owner.getEntityWorld().isChunkLoaded(first)) continue;
            beginBatch(owner,job,plan);return;
        }
    }
    private void validate(Preview p) {
        ServerPlayerEntity player=server.getPlayerManager().getPlayer(p.owner);
        if(player==null || !eligible(player) || !player.getEntityWorld().getRegistryKey().equals(p.world)
                || player.squaredDistanceTo(p.origin.getX(),p.origin.getY(),p.origin.getZ())>32*32) { previews.remove(p.owner);return; }
        ServerWorld world=player.getEntityWorld();
        long until=System.nanoTime()+store.data().config.tickMicros*1000L;
        for(int n=0;p.cursor<p.plan.volume() && n<store.data().config.scanBudget && System.nanoTime()<until;n++,p.cursor++) {
            var relative=p.plan.volumePoint(p.cursor);BlockPos pos=p.origin.add(relative.x(),relative.y(),relative.z());
            if(!world.isInHeightLimit(pos.getY()) || !world.getWorldBorder().contains(pos) || !world.isChunkLoaded(pos)
                    || !api.canBuild(player,world,pos) || !world.getBlockState(pos).isAir() || !world.getFluidState(pos).isEmpty()) {
                reject(p,player,"Объём занят, чанк не загружен или нет права строительства: "+pos.toShortString());return;
            }
            if(relative.y()==p.plan.minY()) {
                BlockPos below=pos.down();
                if(!api.canBuild(player,world,below) || !world.getBlockState(below).isSolidBlock(world,below) || !world.getFluidState(below).isEmpty()) {
                    reject(p,player,"Нужна ровная сплошная сухая опора: "+below.toShortString());return;
                }
            }
        }
        if(p.cursor==p.plan.volume()) {
            previews.remove(p.owner);
            if(!limits(player,p) || !hasMaterials(player,p.plan)) { api.reply(player,"Лимиты или материалы изменились. Повторите предпросмотр.");return; }
            awaitingBalance.add(p.owner);
            long price=store.data().config.price(p.plan.id());
            api.balance(p.owner).whenComplete((balance,error)->server.execute(()-> {
                awaitingBalance.remove(p.owner);
                ServerPlayerEntity current=server.getPlayerManager().getPlayer(p.owner);
                if(!store.ready() || current==null) return;
                if(error!=null || balance==null || balance<price) { api.reply(current,"Недостаточно денег или хранилище денег недоступно.");return; }
                if(!eligible(current) || !store.data().config.buildingPurchasesEnabled || !limits(current,p) || !hasMaterials(current,p.plan)) return;
                ContentData.Job job=new ContentData.Job();job.id=UUID.randomUUID().toString();job.owner=p.owner.toString();
                job.world=p.world.getValue().toString();job.plan=p.plan.id();job.hash=p.plan.fingerprint();job.x=p.origin.getX();job.y=p.origin.getY();job.z=p.origin.getZ();job.price=price;job.createdAt=System.currentTimeMillis();
                store.change(s->{
                    if(PlacementRules.ownerCapReached(s,job.owner) || s.jobs.size()>=s.config.maxJobs
                            || s.jobs.values().stream().filter(j->!j.terminal()).count()>=s.config.maxActiveJobs
                            || s.jobs.values().stream().anyMatch(j->!j.terminal() && j.owner.equals(job.owner))) throw new IllegalStateException("Concurrent limit change");
                    var box=PlacementRules.bounds(p.plan,job.x,job.y,job.z);
                    if(s.jobs.values().stream().anyMatch(j->!j.stage.equals("ABANDONED") && j.world.equals(job.world)
                            && box.intersects(PlacementRules.bounds(plans.get(j.plan),j.x,j.y,j.z)))) throw new IllegalStateException("Concurrent reservation conflict");
                    s.jobs.put(job.id,job);
                },()->charge(job.id),err->api.reply(current,"Резервирование отклонено: ничего не списано."));
            }));
        }
    }
    private void reject(Preview preview,ServerPlayerEntity player,String why) { previews.remove(preview.owner);api.reply(player,why+" Ничего не списано."); }
    private void charge(String id) {
        var job=store.data().jobs.get(id);if(job==null || busy.contains(id) || !Set.of("CHARGING","PAYMENT_REVIEW").contains(job.stage)) return;
        busy.add(id);
        api.debit(UUID.fromString(job.owner),job.price,job.operationId(),"Городской чертёж "+job.plan).whenComplete((paid,error)->server.execute(()-> {
            if(!store.ready()) { busy.remove(id);return; }
            store.change(s->{var j=s.jobs.get(id);
                if(error!=null) {j.stage="PAYMENT_REVIEW";j.problem="Неизвестный результат платежа. Повторять только с тем же ID операции.";}
                else if(Boolean.TRUE.equals(paid)) {j.paid=true;j.stage="WAITING_MATERIALS";j.problem="";}
                else {j.stage="ABANDONED";j.problem="Платёж отклонён, предметы не изымались.";}
            },()->{busy.remove(id);var j=store.data().jobs.get(id);api.audit(j.owner,"building.payment",id+":"+j.stage);
                ServerPlayerEntity owner=server.getPlayerManager().getPlayer(UUID.fromString(j.owner));
                if(owner!=null) { if(j.paid) takeMaterials(owner,id);else api.reply(owner,j.problem); }
            },err->busy.remove(id));
        }));
    }
    private void takeMaterials(ServerPlayerEntity player,String id) {
        var job=store.data().jobs.get(id);
        if(job==null || !job.owner.equals(player.getUuidAsString()) || !job.stage.equals("WAITING_MATERIALS") || busy.contains(id) || !eligible(player)) return;
        BlockPlan plan=plans.get(job.plan);
        if(!hasMaterials(player,plan)) { api.reply(player,"Оплата уже сохранена. Принесите материалы и нажмите «Продолжить» — повторного платежа не будет.");return; }
        busy.add(id);
        // This is deliberately an intent, not a claim that inventory and SQLite commit atomically.
        store.change(s->{var j=s.jobs.get(id);if(!j.stage.equals("WAITING_MATERIALS"))throw new IllegalStateException("Unexpected material stage");j.stage="MATERIAL_INTENT";},()-> {
            ServerPlayerEntity current=server.getPlayerManager().getPlayer(player.getUuid());
            if(current==null || !eligible(current) || !hasMaterials(current,plan)) {
                store.change(s->{s.jobs.get(id).stage="WAITING_MATERIALS";},()->busy.remove(id),err->busy.remove(id));return;
            }
            removeMaterials(current,plan);
            store.change(s->{var j=s.jobs.get(id);j.materialsHeld=true;j.stage="PLACING";j.problem="";},()-> {
                busy.remove(id);api.audit(job.owner,"building.materials_reserved",id);api.reply(current,"Стройка началась. Оставайтесь не дальше 64 блоков. ID: "+id);
            },err->{busy.remove(id);api.reply(current,"Ошибка сохранения после изъятия материалов. Стройка остановлена: нужна ручная сверка, автоматического повтора нет.");});
        },err->busy.remove(id));
    }
    private void beginBatch(ServerPlayerEntity owner,ContentData.Job job,BlockPlan plan) {
        int target=Math.min(plan.cells().size(),job.cursor+store.data().config.placementBudget);busy.add(job.id);
        store.change(s->{var j=s.jobs.get(job.id);if(!j.stage.equals("PLACING"))throw new IllegalStateException("Unexpected placement stage");j.batchEnd=target;j.stage="BATCH";},()-> {
            var latest=store.data().jobs.get(job.id);
            ServerPlayerEntity player=server.getPlayerManager().getPlayer(UUID.fromString(latest.owner));
            ServerWorld world=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of(latest.world)));
            int cursor=latest.cursor;String problem="";
            long until=System.nanoTime()+store.data().config.tickMicros*1000L;
            if(player==null || world==null || !eligible(player) || player.getEntityWorld()!=world || player.squaredDistanceTo(latest.x,latest.y,latest.z)>64*64) problem="Владелец отошёл или вошёл в бой.";
            else while(cursor<target && System.nanoTime()<until) {
                var cell=plan.cells().get(cursor);BlockPos pos=at(latest,cell);
                if(!world.isChunkLoaded(pos)) break;
                var current=world.getBlockState(pos);var expected=WorldAccess.state(cell.block());
                var action=PlacementRules.cell(api.canBuild(player,world,pos) && world.getWorldBorder().contains(pos),true,current.isAir(),!current.getFluidState().isEmpty(),current.equals(expected),true);
                if(action==PlacementRules.CellAction.STOP) {problem="Конфликт или изменились права: "+pos.toShortString();break;}
                if(action==PlacementRules.CellAction.PLACE && !world.setBlockState(pos,expected,Block.NOTIFY_LISTENERS)) {problem="Блок не принят миром: "+pos.toShortString();break;}
                cursor++;
            }
            int next=cursor;String why=problem;
            store.change(s->{var j=s.jobs.get(job.id);j.cursor=next;j.batchEnd=next;j.problem=why;
                j.stage=!why.isEmpty()?"PAUSED":next==plan.cells().size()?"DONE":"PLACING";
            },()->{busy.remove(job.id);var j=store.data().jobs.get(job.id);
                if(j.stage.equals("DONE")){api.audit(j.owner,"building.complete",j.id);replyOwner(j,"Готово: "+PlacementRules.name(j.plan)+". Автопочинки и пассивного дохода нет.");}
                else if(j.stage.equals("PAUSED")) replyOwner(j,j.problem+" Работа приостановлена без удаления блоков.");
            },error->busy.remove(job.id));
        },error->busy.remove(job.id));
    }
    private void finish(String id) { store.change(s->{var j=s.jobs.get(id);j.stage="DONE";j.batchEnd=j.cursor;},()->{}); }
    private void pause(String id,String reason) { store.change(s->{var j=s.jobs.get(id);j.stage="PAUSED";j.problem=reason;},()->replyOwner(store.data().jobs.get(id),reason)); }
    private void replyOwner(ContentData.Job job,String text) { var p=server.getPlayerManager().getPlayer(UUID.fromString(job.owner));if(p!=null)api.reply(p,text); }
    private void jobMenu(ServerPlayerEntity player,String id) {
        var job=store.data().jobs.get(id);if(job==null || !job.owner.equals(player.getUuidAsString())) return;
        api.reply(player,"Работа "+id+": "+job.stage+". "+job.problem);
        api.menu(player,"Работа · "+PlacementRules.name(job.plan),List.of(
                ContentFeature.entry(Items.LIME_DYE,"Продолжить без повторной оплаты",()->continueOwn(player,id)),
                ContentFeature.entry(Items.BARRIER,"Оставить как есть · без возврата",()->api.menu(player,"Подтвердите отказ · без возврата",List.of(
                        ContentFeature.entry(Items.RED_DYE,"Да: оставить блоки, потерять остаток материалов",()->abandon(player,id)),
                        ContentFeature.entry(Items.PAPER,"Назад",()->jobMenu(player,id)))))));
    }
    private void continueOwn(ServerPlayerEntity player,String id) {
        var job=store.data().jobs.get(id);if(job==null || !job.owner.equals(player.getUuidAsString()) || busy.contains(id))return;
        if(job.stage.equals("WAITING_MATERIALS")) takeMaterials(player,id);
        else if(job.stage.equals("PAUSED") && job.paid && job.materialsHeld) store.change(s->{s.jobs.get(id).stage="PLACING";s.jobs.get(id).problem="";},()->api.reply(player,"Продолжение запланировано. Права и блоки будут проверяться повторно."));
        else api.reply(player,"Состояние "+job.stage+": после рестарта/ошибки нужна сверка персонала, не повторное списание.");
    }
    private void abandon(ServerPlayerEntity player,String id) {
        var j=store.data().jobs.get(id);
        if(j==null || !j.owner.equals(player.getUuidAsString()) || j.terminal() || busy.contains(id) || !store.idle())return;
        if(Set.of("CHARGING","PAYMENT_REVIEW","MATERIAL_INTENT","MATERIAL_REVIEW","BATCH","RECOVERY").contains(j.stage)) {api.reply(player,"Сначала персонал должен сверить незавершённую операцию.");return;}
        store.change(s->{var job=s.jobs.get(id);job.stage="ABANDONED";job.problem="Владелец подтвердил отказ без возврата.";},()->{api.audit(j.owner,"building.abandon_no_refund",id);api.reply(player,"Оставлено как есть. Блоки не удалены, возврата нет.");});
    }
    /** A privileged operator explicitly certifies inventory/world reconciliation; reason is mandatory. */
    void recover(String id,String actor,String reason) {
        if(!store.ready() || !PlacementRules.validReason(reason) || busy.contains(id)) return;
        var job=store.data().jobs.get(id);if(job==null || job.terminal())return;
        if(job.stage.equals("PAYMENT_REVIEW")) {api.audit(actor,"building.payment_retry_same_id",id+":"+reason);charge(id);return;}
        if(!job.paid || !Set.of("MATERIAL_REVIEW","RECOVERY","PAUSED").contains(job.stage))return;
        store.change(s->{var j=s.jobs.get(id);j.materialsHeld=true;j.stage="PLACING";j.problem="";},()->api.audit(actor,"building.manual_reconciliation_and_resume",id+":"+reason));
    }
    boolean reserved(World world,BlockPos pos) {
        if(store.lastSnapshot()==null) return false;
        for(var j:store.lastSnapshot().jobs.values()) if(PlacementRules.isProtected(j) && j.world.equals(world.getRegistryKey().getValue().toString())
                && PlacementRules.bounds(plans.get(j.plan),j.x,j.y,j.z).contains(pos.getX(),pos.getY(),pos.getZ())) return true;
        return false;
    }
    private static BlockPos at(ContentData.Job job,BlockPlan.Cell c) {return new BlockPos(job.x+c.x(),job.y+c.y(),job.z+c.z());}
    private static String label(String id) { return switch(id) {
        case "minecraft:stone_bricks"->"каменные кирпичи";case "minecraft:oak_log"->"дубовые брёвна";
        case "minecraft:spruce_planks"->"еловые доски";case "minecraft:spruce_slab"->"еловые плиты";
        case "minecraft:glass"->"стекло";case "minecraft:barrel"->"бочки";case "minecraft:crafting_table"->"верстаки";
        case "minecraft:glowstone"->"светокамень";case "minecraft:bricks"->"кирпичные блоки";
        case "minecraft:smithing_table"->"кузнечный стол";case "minecraft:stonecutter"->"камнерез";
        default->id;}; }
    private static boolean hasMaterials(ServerPlayerEntity p,BlockPlan plan) {
        for(var need:plan.materials().entrySet()) {
            Item item=Registries.ITEM.get(Identifier.of(need.getKey())); if(item==Items.AIR)return false;
            ItemStack expected=new ItemStack(item);int count=0;
            for(int i=0;i<36;i++) {var stack=p.getInventory().getStack(i);if(ItemStack.areItemsAndComponentsEqual(stack,expected))count+=stack.getCount();}
            if(count<need.getValue())return false;
        }
        return true;
    }
    private static void removeMaterials(ServerPlayerEntity p,BlockPlan plan) {
        if(!hasMaterials(p,plan))throw new IllegalStateException("Inventory changed");
        for(var need:plan.materials().entrySet()) {
            ItemStack expected=new ItemStack(Registries.ITEM.get(Identifier.of(need.getKey())));int left=need.getValue();
            for(int i=0;i<36 && left>0;i++) {var stack=p.getInventory().getStack(i);if(ItemStack.areItemsAndComponentsEqual(stack,expected)){int take=Math.min(left,stack.getCount());stack.decrement(take);left-=take;}}
            if(left!=0)throw new IllegalStateException("Incomplete material removal");
        }
        p.getInventory().markDirty();p.currentScreenHandler.sendContentUpdates();
    }
    private void particles(ServerPlayerEntity p,Preview preview) {
        // Client-only particles: no fake collision blocks or preview entities; 8 corners + 12 edge midpoints.
        var b=PlacementRules.bounds(preview.plan,preview.origin.getX(),preview.origin.getY(),preview.origin.getZ());
        Set<BlockPos> points=new HashSet<>();
        int midX=(b.minX()+b.maxX())/2,midZ=(b.minZ()+b.maxZ())/2,midY=(b.minY()+b.maxY())/2;
        for(int x:new int[]{b.minX(),b.maxX()+1})for(int y:new int[]{b.minY(),b.maxY()+1})for(int z:new int[]{b.minZ(),b.maxZ()+1})points.add(new BlockPos(x,y,z));
        for(int y:new int[]{b.minY(),b.maxY()+1}) {for(int x:new int[]{b.minX(),b.maxX()+1})points.add(new BlockPos(x,y,midZ));for(int z:new int[]{b.minZ(),b.maxZ()+1})points.add(new BlockPos(midX,y,z));}
        for(int x:new int[]{b.minX(),b.maxX()+1})for(int z:new int[]{b.minZ(),b.maxZ()+1})points.add(new BlockPos(x,midY,z));
        for(BlockPos pos:points)p.getEntityWorld().spawnParticles(p,ParticleTypes.END_ROD,false,false,pos.getX()+0.05,pos.getY()+0.1,pos.getZ()+0.05,1,0,0,0,0);
    }
    void disconnect(UUID player) {previews.remove(player);}
    void stop() {previews.clear();busy.clear();awaitingBalance.clear();}
}
