package ru.warland.content;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanSafetyTest {
    @BeforeAll static void bootstrapMinecraftRegistries() {SharedConstants.createGameVersion();Bootstrap.initialize();}
    @Test void everyGroundDecorationHasFoundation() {
        var p=Layouts.capital();Set<BlockPlan.Point> base=new HashSet<>();
        for(var c:p.cells())if(c.y()==0)base.add(new BlockPlan.Point(c.x(),0,c.z()));
        for(var c:p.cells())if(c.y()==1)assertTrue(base.contains(new BlockPlan.Point(c.x(),0,c.z())),c.toString());
    }
    @Test void everyBlueprintStateResolvesAgainstActual12111Registry() {
        Set<String> blocks=new HashSet<>();for(var c:Layouts.capital().cells())blocks.add(c.block());
        for(var p:Layouts.buildings().values())for(var c:p.cells())blocks.add(c.block());
        for(String block:blocks)assertFalse(WorldAccess.state(block).isAir(),block);
    }
    @Test void onlyNaturalTransientValuesAreIgnoredByIntegrityCheck() {
        assertTrue(WorldAccess.compatible(WorldAccess.state("minecraft:oak_leaves[persistent=true,distance=1]"),WorldAccess.state("minecraft:oak_leaves[persistent=true]")));
        assertFalse(WorldAccess.compatible(WorldAccess.state("minecraft:oak_leaves[persistent=false]"),WorldAccess.state("minecraft:oak_leaves[persistent=true]")));
        assertTrue(WorldAccess.compatible(WorldAccess.state("minecraft:target[power=10]"),WorldAccess.state("minecraft:target")));
        assertFalse(WorldAccess.compatible(WorldAccess.state("minecraft:stone"),WorldAccess.state("minecraft:stone_bricks")));
    }
    @Test void capitalDimensionDecodesWithActual12111WorldgenCodec() throws Exception {
        try(var stream=getClass().getResourceAsStream("/data/warland/dimension/capital.json")) {
            assertNotNull(stream);
            var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
            var ops=RegistryOps.of(JsonOps.INSTANCE,BuiltinRegistries.createWrapperLookup());
            var dimension=DimensionOptions.CODEC.parse(ops,json).getOrThrow();
            assertInstanceOf(FlatChunkGenerator.class,dimension.chunkGenerator());
        }
    }
    interface Probe {String run();}
    @Test void readOnlyTerminalPhasePrecedesPreviouslyRegisteredCoreVeto() {
        Event<Probe> event=EventFactory.createArrayBacked(Probe.class,listeners->()->{
            for(Probe p:listeners){String result=p.run();if(result!=null)return result;}return null;
        });
        event.register(()->"core-deny");
        Identifier terminal=Identifier.of("warland","content_terminals");
        event.addPhaseOrdering(terminal,Event.DEFAULT_PHASE);event.register(terminal,()->"terminal-read-only");
        assertEquals("terminal-read-only",event.invoker().run());
    }
    @Test void airFluidAndUnknownBlocksFailClosed() {
        assertThrows(IllegalArgumentException.class,()->WorldAccess.state("minecraft:air"));
        assertThrows(IllegalArgumentException.class,()->WorldAccess.state("minecraft:water"));
        assertThrows(IllegalArgumentException.class,()->WorldAccess.state("warland:not_registered"));
    }
}
