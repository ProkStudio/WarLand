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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanSafetyTest {
    static void bootstrapMinecraftRegistries() {SharedConstants.createGameVersion();Bootstrap.initialize();}
    @Test void everyGroundDecorationHasFoundation() {
        var p=Layouts.capital();Set<BlockPlan.Point> base=new HashSet<>();
        for(var c:p.cells())if(c.y()==0)base.add(new BlockPlan.Point(c.x(),0,c.z()));
        for(var c:p.cells())if(c.y()==1)assertTrue(base.contains(new BlockPlan.Point(c.x(),0,c.z())),c.toString());
    }
    @Test @Disabled("Requires a Fabric Knot runtime: plain Loom/JUnit lacks Minecraft access transformations; see CONTENT.md")
    void everyBlueprintStateResolvesAgainstActual12111Registry() {
        bootstrapMinecraftRegistries();
        Set<String> blocks=new HashSet<>();for(var c:Layouts.capital().cells())blocks.add(c.block());
        for(var p:Layouts.buildings().values())for(var c:p.cells())blocks.add(c.block());
        for(String block:blocks)assertFalse(WorldAccess.state(block).isAir(),block);
    }
    @Test @Disabled("Requires a Fabric Knot runtime: plain Loom/JUnit lacks Minecraft access transformations; see CONTENT.md")
    void onlyNaturalTransientValuesAreIgnoredByIntegrityCheck() {
        bootstrapMinecraftRegistries();
        assertTrue(WorldAccess.compatible(WorldAccess.state("minecraft:oak_leaves[persistent=true,distance=1]"),WorldAccess.state("minecraft:oak_leaves[persistent=true]")));
        assertFalse(WorldAccess.compatible(WorldAccess.state("minecraft:oak_leaves[persistent=false]"),WorldAccess.state("minecraft:oak_leaves[persistent=true]")));
        assertTrue(WorldAccess.compatible(WorldAccess.state("minecraft:target[power=10]"),WorldAccess.state("minecraft:target")));
        assertFalse(WorldAccess.compatible(WorldAccess.state("minecraft:stone"),WorldAccess.state("minecraft:stone_bricks")));
    }
    @Test @Disabled("Requires a Fabric Knot runtime: plain Loom/JUnit lacks Minecraft access transformations; see CONTENT.md")
    void capitalDimensionDecodesWithActual12111WorldgenCodec() throws Exception {
        bootstrapMinecraftRegistries();
        try(var stream=getClass().getResourceAsStream("/data/warland/dimension/capital.json")) {
            assertNotNull(stream);
            var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
            var ops=RegistryOps.of(JsonOps.INSTANCE,BuiltinRegistries.createWrapperLookup());
            var dimension=DimensionOptions.CODEC.parse(ops,json).getOrThrow();
            assertInstanceOf(FlatChunkGenerator.class,dimension.chunkGenerator());
        }
    }
    @Test void dimensionResourceIsStrictlyVoidAndDoesNotTargetOverworld() throws Exception {
        try(var stream=getClass().getResourceAsStream("/data/warland/dimension/capital.json")) {
            assertNotNull(stream);
            var root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            var generator=root.getAsJsonObject("generator");assertEquals("minecraft:flat",generator.get("type").getAsString());
            var settings=generator.getAsJsonObject("settings");assertEquals("minecraft:the_void",settings.get("biome").getAsString());
            assertEquals(0,settings.getAsJsonArray("structure_overrides").size());
            assertEquals(1,settings.getAsJsonArray("layers").size());
            assertEquals("minecraft:air",settings.getAsJsonArray("layers").get(0).getAsJsonObject().get("block").getAsString());
            assertFalse(settings.get("lakes").getAsBoolean());assertFalse(settings.get("features").getAsBoolean());
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
    @Test @Disabled("Requires a Fabric Knot runtime: plain Loom/JUnit lacks Minecraft access transformations; see CONTENT.md")
    void airFluidAndUnknownBlocksFailClosed() {
        bootstrapMinecraftRegistries();
        assertThrows(IllegalArgumentException.class,()->WorldAccess.state("minecraft:air"));
        assertThrows(IllegalArgumentException.class,()->WorldAccess.state("minecraft:water"));
        assertThrows(IllegalArgumentException.class,()->WorldAccess.state("warland:not_registered"));
    }
}
