package ru.warland.content;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.warland.api.WarLandApi;

/** Nondestructive capital generator. Existing non-air voxels are never replaced, even by staff. */
final class CapitalService {
    static final RegistryKey<World> WORLD=RegistryKey.of(RegistryKeys.WORLD,Identifier.of("warland","capital"));
    static final BlockPos ARRIVAL=new BlockPos(0,Layouts.CAPITAL_Y+1,12);
    private final WarLandApi api;
    private final ContentStore store;
    private final MinecraftServer server;
    private final BlockPlan plan;
    private final List<BlockPlan.Cell> cells;
    private final WorldAccess.ChunkLease lease=new WorldAccess.ChunkLease();
    private int scan,verify;
    private boolean registered,verified,writesPending;
    private final String hash;
    private final int chunkMinX,chunkMinZ,chunksX,chunksZ,chunkVolume;
    CapitalService(WarLandApi api,ContentStore store,MinecraftServer server) {
        this.api=api; this.store=store; this.server=server;
        plan=Layouts.capital(); hash=plan.fingerprint();
        cells=plan.cells().stream().sorted(Comparator.comparingInt((BlockPlan.Cell c)->c.z()>>4)
                .thenComparingInt(c->c.x()>>4).thenComparingInt(BlockPlan.Cell::y).thenComparingInt(BlockPlan.Cell::z).thenComparingInt(BlockPlan.Cell::x)).toList();
        chunkMinX=plan.minX()>>4; chunkMinZ=plan.minZ()>>4;
        chunksX=(plan.maxX()>>4)-chunkMinX+1; chunksZ=(plan.maxZ()>>4)-chunkMinZ+1;
        chunkVolume=256*plan.height();
    }
    boolean isReady() { return registered; }
    boolean owns(World world) { return world.getRegistryKey().equals(WORLD); }
    void start() {
        var data=store.data().capital;
        if(!data.hash.isEmpty() && !hash.equals(data.hash)) { pause("Версия чертежа изменилась: автоматическая миграция запрещена."); return; }
        if(data.cursor>cells.size()) { pause("Курсор столицы повреждён."); return; }
        if(data.stage.equals("NEW")) store.change(s->{s.capital.hash=hash;s.capital.stage="SCANNING";},()->{});
    }
    void tick() {
        if(!store.idle() || writesPending || registered) return;
        ServerWorld world=server.getWorld(WORLD);
        if(world==null) return;
        var c=store.data().capital;
        if(c.stage.equals("PAUSED") || c.stage.equals("NEW")) return;
        long until=System.nanoTime()+store.data().config.tickMicros*1000L;
        if(!verified && (c.stage.equals("BUILDING") || c.stage.equals("READY"))) {
            int target=c.stage.equals("READY")?cells.size():c.cursor;
            for(int n=0;verify<target && n<store.data().config.scanBudget && System.nanoTime()<until;n++) {
                var cell=cells.get(verify); BlockPos p=position(cell);
                if(!lease.ready(world,p)) return;
                if(!WorldAccess.compatible(world.getBlockState(p),WorldAccess.state(cell.block()))) { pause("Изменён сохранённый блок "+p.toShortString()+". Самовосстановление выключено."); return; }
                verify++;
            }
            if(verify<target) return;
            verified=true; lease.release();
        }
        if(c.stage.equals("READY")) { register(world); return; }
        if(c.stage.equals("SCANNING")) {
            int total=chunksX*chunksZ*chunkVolume;
            for(int n=0;scan<total && n<store.data().config.scanBudget && System.nanoTime()<until;n++,scan++) {
                BlockPos p=scanPosition(scan);
                if(!lease.ready(world,p)) return;
                if(!world.isInHeightLimit(p.getY()) || !world.getWorldBorder().contains(p) || !world.getBlockState(p).isAir()) {
                    pause("Площадка не пуста: "+p.toShortString()+". Ничего не удалено."); return;
                }
            }
            if(scan>=total) { lease.release(); store.change(s->{s.capital.stage="BUILDING";s.capital.cursor=0;},()->{}); }
            return;
        }
        if(c.stage.equals("BUILDING")) {
            int cursor=c.cursor;
            for(int n=0;cursor<cells.size() && n<store.data().config.placementBudget && System.nanoTime()<until;n++) {
                var cell=cells.get(cursor); BlockPos p=position(cell);
                if(!lease.ready(world,p)) break;
                var expected=WorldAccess.state(cell.block()); var existing=world.getBlockState(p);
                var action=PlacementRules.cell(true,true,existing.isAir(),!existing.getFluidState().isEmpty(),WorldAccess.compatible(existing,expected),true);
                if(action==PlacementRules.CellAction.STOP) { pause("Конфликт в "+p.toShortString()+". Чужой блок оставлен."); return; }
                if(action==PlacementRules.CellAction.PLACE && !world.setBlockState(p,expected,Block.NOTIFY_LISTENERS)) { pause("Не удалось разместить "+p.toShortString()); return; }
                cursor++;
            }
            if(cursor!=c.cursor) {
                int next=cursor; writesPending=true;
                store.change(s->{s.capital.cursor=next;if(next==cells.size())s.capital.stage="READY";},()->{writesPending=false; if(next==cells.size()){verified=false;verify=0;lease.release();}},error->writesPending=false);
            }
        }
    }
    private BlockPos scanPosition(int cursor) {
        int chunk=cursor/chunkVolume,local=cursor%chunkVolume;
        int x=((chunkMinX+chunk%chunksX)<<4)+(local%16);
        int z=((chunkMinZ+chunk/chunksX)<<4)+((local/16)%16);
        return new BlockPos(x,Layouts.CAPITAL_Y+plan.minY()+local/256,z);
    }
    private static BlockPos position(BlockPlan.Cell c) { return new BlockPos(c.x(),Layouts.CAPITAL_Y+c.y(),c.z()); }
    private void register(ServerWorld world) {
        if(!world.isChunkLoaded(ARRIVAL)) { lease.ready(world,ARRIVAL); return; }
        if(!world.getBlockState(ARRIVAL).isAir() || !world.getBlockState(ARRIVAL.up()).isAir()
                || !world.getBlockState(ARRIVAL.down()).isSolidBlock(world,ARRIVAL.down())) { pause("Точка прибытия занята."); return; }
        api.setSafeSpawn(world,ARRIVAL,Layouts.CAPITAL_RADIUS);
        api.registerWarp("capital",world,ARRIVAL);
        for(var zone:Layouts.ZONES) api.registerWarp("capital_"+zone.id(),world,new BlockPos(zone.x(),Layouts.CAPITAL_Y+1,zone.z()+4));
        registered=true;lease.release();
        api.audit("content","capital.ready",hash);
    }
    void updateLoadedSigns() {
        if(!registered) return;
        ServerWorld world=server.getWorld(WORLD); if(world==null) return;
        for(var zone:Layouts.ZONES) {
            BlockPos p=new BlockPos(zone.x(),Layouts.CAPITAL_Y+3,zone.z());
            if(!world.isChunkLoaded(p)) continue;
            if(world.getBlockEntity(p) instanceof SignBlockEntity sign && !sign.isWaxed()) {
                SignText text=new SignText().withMessage(0,Text.literal("WARLAND"))
                        .withMessage(1,Text.literal(switch(zone.id()){case "market"->"ТОРГОВЛЯ";case "quests"->"ЗАДАНИЯ";case "transport"->"ТРАНСПОРТ";default->"ОБУЧЕНИЕ";}))
                        .withMessage(2,Text.literal("Нажмите ПКМ"));
                sign.setText(text,true);sign.setText(text,false);sign.setWaxed(true);sign.markDirty();
                world.updateListeners(p,world.getBlockState(p),world.getBlockState(p),Block.NOTIFY_LISTENERS);
            }
        }
    }
    void resume(String actor,String reason) {
        if(!PlacementRules.validReason(reason)) return;
        if(!store.data().capital.hash.equals(hash)) return;
        verify=0;verified=false;scan=0;registered=false;
        store.change(s->{s.capital.stage=s.capital.cursor>0?"BUILDING":"SCANNING";s.capital.problem="";},()->api.audit(actor,"capital.resume",reason));
    }
    private void pause(String reason) { lease.release();registered=false;store.change(s->{s.capital.stage="PAUSED";s.capital.problem=reason;},()->api.audit("content","capital.paused",reason)); }
    String status() {
        if(!store.ready()) return "Хранилище недоступно: модуль закрыт.";
        var c=store.data().capital;
        return "Столица: "+c.stage+"; блоки "+c.cursor+"/"+cells.size()+". "+c.problem+(server.getWorld(WORLD)==null?" Измерение warland:capital отсутствует.":"");
    }
    void stop() { lease.release(); }
}
