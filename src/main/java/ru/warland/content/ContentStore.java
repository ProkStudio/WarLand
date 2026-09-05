package ru.warland.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.warland.api.WarLandApi;

/** Serialized async writes. Never join a future or touch Minecraft from a storage completion thread. */
final class ContentStore {
    static final String NAMESPACE="content", KEY="state.v1";
    static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private static final Logger LOG=LoggerFactory.getLogger("warland-content");
    private final WarLandApi api;
    private final java.util.concurrent.Executor server;
    private final ArrayDeque<Write> queue=new ArrayDeque<>();
    private ContentData data;
    private boolean writing,closed;
    private Throwable failure;
    private record Write(Consumer<ContentData> edit,Runnable after,Consumer<Throwable> failed) {}
    ContentStore(WarLandApi api,MinecraftServer server) { this(api,(java.util.concurrent.Executor)server::execute); }
    ContentStore(WarLandApi api,java.util.concurrent.Executor executor) { this.api=api;this.server=executor; }
    ContentData lastSnapshot() { return data; }
    void load(Runnable loaded) {
        api.getState(NAMESPACE,KEY).whenComplete((json,error)->server.execute(()-> {
            if(closed) return;
            if(error!=null) { fail(error); return; }
            try {
                data=(json==null || json.isBlank()) ? new ContentData() : JSON.fromJson(json,ContentData.class);
                if(data==null) throw new IllegalArgumentException("Null content state");
                data.validate();
                change(ContentData::quarantineInterruptedJobs,loaded,this::fail);
            } catch(RuntimeException bad) { fail(bad); }
        }));
    }
    boolean ready() { return data!=null && failure==null && !closed; }
    boolean idle() { return ready() && !writing && queue.isEmpty(); }
    ContentData data() { if(!ready()) throw new IllegalStateException("Content storage unavailable"); return data; }
    void change(Consumer<ContentData> edit,Runnable after,Consumer<Throwable> failed) {
        if(!ready()) { failed.accept(new IllegalStateException("Content storage unavailable",failure)); return; }
        if(queue.size()>=64) { failed.accept(new IllegalStateException("Content write queue full")); return; }
        queue.add(new Write(edit,after,failed)); pump();
    }
    void change(Consumer<ContentData> edit,Runnable after) { change(edit,after,this::fail); }
    private void pump() {
        if(writing || !ready() || queue.isEmpty()) return;
        Write write=queue.remove();
        ContentData next;
        String json;
        try {
            next=JSON.fromJson(JSON.toJson(data),ContentData.class);
            write.edit.accept(next); next.revision=Math.addExact(next.revision,1); next.validate();
            json=JSON.toJson(next);
            if(json.length()>4_000_000) throw new IllegalStateException("Content state too large");
        } catch(RuntimeException rejected) { write.failed.accept(rejected); pump(); return; }
        writing=true;
        api.setState(NAMESPACE,KEY,json).whenComplete((unused,error)->server.execute(()-> {
            if(closed) return;
            writing=false;
            if(error!=null) { fail(error); write.failed.accept(error); return; }
            data=next;
            try { write.after.run(); } catch(RuntimeException callback) { LOG.error("Content completion failed; state retained",callback); }
            pump();
        }));
    }
    private void fail(Throwable error) {
        failure=error; writing=false;
        LOG.error("Content storage disabled, no further world/economy writes will run",error);
        while(!queue.isEmpty()) { try { queue.remove().failed.accept(error); } catch(RuntimeException ignored) {} }
    }
    void close() { closed=true; queue.clear(); }
}
