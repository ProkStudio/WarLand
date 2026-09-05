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
    private static <T extends Comparable<T>> BlockState apply(BlockState state,Property<T> property,String value) {
        return state.with(property,property.parse(value).orElseThrow(()->new IllegalArgumentException("Bad block property")));
    }
    /** Holds ONE non-serialized, loading-only chunk ticket; no synchronous getChunk/generation waits. */
    static final class ChunkLease {
        private static final ChunkTicketType TYPE=new ChunkTicketType(40,ChunkTicketType.FOR_LOADING);
        private ServerWorld world;
        private ChunkPos chunk;
        boolean ready(ServerWorld next,BlockPos pos) {
            ChunkPos wanted=new ChunkPos(pos);
            if(world!=next || !wanted.equals(chunk)) { release(); world=next; chunk=wanted; }
            world.getChunkManager().addTicket(TYPE,chunk,0);
            return world.isChunkLoaded(chunk.x,chunk.z);
        }
        void release() {
            if(world!=null && chunk!=null) world.getChunkManager().removeTicket(TYPE,chunk,0);
            world=null; chunk=null;
        }
    }
}
