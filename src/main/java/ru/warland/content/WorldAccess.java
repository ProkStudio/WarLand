package ru.warland.content;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

final class WorldAccess {
    private WorldAccess() {}
    private static final Map<String,BlockState> STATES=new HashMap<>();
    static BlockState state(String specification) {
        return STATES.computeIfAbsent(specification,key->{
            String[] parts=key.split("\\[",2);
            Identifier id=Identifier.of(parts[0]);
            if(!Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Unknown block "+id);
            BlockState result=Registries.BLOCK.get(id).getDefaultState();
            if(parts.length==2) for(String pair:parts[1].substring(0,parts[1].length()-1).split(",")) {
                String[] kv=pair.split("=",2);
                Property<?> property=result.getBlock().getStateManager().getProperty(kv[0]);
                if(property==null) throw new IllegalArgumentException("Unknown property "+pair);
                result=apply(result,property,kv[1]);
            }
            if(result.isAir() || !result.getFluidState().isEmpty()) throw new IllegalArgumentException("Air/fluid is forbidden in blueprints");
            return result;
        });
    }
    /** Ignore only vanilla transient leaf distance, wall connections and target pulse power. */
    static boolean compatible(BlockState actual,BlockState expected) {
        if(actual.getBlock()!=expected.getBlock()) return false;
        String block=Registries.BLOCK.getId(expected.getBlock()).getPath();
        for(Property<?> property:expected.getProperties()) {
            String name=property.getName();
            boolean transientValue=(block.endsWith("_leaves") && name.equals("distance"))
                    || (block.endsWith("_wall") && java.util.Set.of("north","south","east","west","up").contains(name))
                    || (block.equals("target") && name.equals("power"));
            if(!transientValue && !java.util.Objects.equals(actual.get(property),expected.get(property))) return false;
        }
        return true;
    }
    private static <T extends Comparable<T>> BlockState apply(BlockState state,Property<T> property,String value) {
        return state.with(property,property.parse(value).orElseThrow(()->new IllegalArgumentException("Bad block property")));
    }
    /** Holds ONE non-serialized, loading-only chunk ticket; no synchronous getChunk/generation waits. */
    static final class ChunkLease {
        private static final ChunkTicketType TYPE=new ChunkTicketType(40,ChunkTicketType.FOR_LOADING);
        private ServerWorld world;
        private ChunkPos chunk;
        private long refreshed=Long.MIN_VALUE;
        private boolean loaded;
        boolean ready(ServerWorld next,BlockPos pos) {
            int x=pos.getX()>>4,z=pos.getZ()>>4;
            if(world!=next || chunk==null || chunk.x!=x || chunk.z!=z) { release(); world=next; chunk=new ChunkPos(x,z); }
            long now=world.getTime();
            if(refreshed!=now) {
                world.getChunkManager().addTicket(TYPE,chunk,0);
                loaded=world.isChunkLoaded(chunk.x,chunk.z);refreshed=now;
            }
            return loaded;
        }
        void release() {
            if(world!=null && chunk!=null) world.getChunkManager().removeTicket(TYPE,chunk,0);
            world=null; chunk=null;refreshed=Long.MIN_VALUE;loaded=false;
        }
    }
}
