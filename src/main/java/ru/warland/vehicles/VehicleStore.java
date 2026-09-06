package ru.warland.vehicles;

import com.google.gson.Gson;
import java.util.concurrent.*;
import java.util.function.*;
import ru.warland.vehicles.VehicleState.Fleet;

/** All entrypoints and completions run on the supplied server executor. No blocking IO in ticks. */
public final class VehicleStore {
    public static final String NAMESPACE="vehicles",KEY="fleet.v1";
    private static final Gson JSON=new Gson();
    private final Supplier<CompletableFuture<String>> read;
    private final Function<String,CompletableFuture<Void>> write;
    private final Executor executor;
    private Fleet fleet;
    private boolean busy,closed,failed;
    public VehicleStore(Supplier<CompletableFuture<String>> read,Function<String,CompletableFuture<Void>> write,Executor executor){this.read=read;this.write=write;this.executor=executor;}
    public Fleet snapshot(){return fleet;}
    public boolean ready(){return fleet!=null&&!closed&&!failed;}
    public boolean idle(){return ready()&&!busy;}
    public void load(){
        if(busy||fleet!=null||closed)throw new IllegalStateException("Already loaded");busy=true;
        try {read.get().whenComplete((raw,error)->executor.execute(()->{
            if(closed)return;
            if(error!=null){failed=true;busy=false;return;}
            try {
                if(raw!=null&&(raw.isBlank()||raw.length()>1_000_000))throw new IllegalArgumentException("Invalid fleet document size");
                Fleet loaded=raw==null?Fleet.empty():JSON.fromJson(raw,Fleet.class);
                if(loaded==null)throw new IllegalArgumentException("Null fleet");
                // Quarantine all interrupted deployments BEFORE accepting a new command.
                commit(loaded.recoverInterrupted(),()->{},()->{});
            } catch(RuntimeException invalid){failed=true;busy=false;}
        }));}catch(RuntimeException unavailable){failed=true;busy=false;}
    }
    public boolean change(UnaryOperator<Fleet> edit,Runnable after,Runnable failure){
        if(!idle())return false;
        Fleet next=edit.apply(fleet);busy=true;commit(next,after,failure);return true;
    }
    private void commit(Fleet next,Runnable after,Runnable failure){
        String json=JSON.toJson(next);
        if(json.length()>1_000_000){failed=true;busy=false;failure.run();return;}
        try {write.apply(json).whenComplete((ignored,error)->executor.execute(()->{
            if(closed)return;
            busy=false;
            if(error!=null){failed=true;failure.run();return;}
            fleet=next;after.run();
        }));}catch(RuntimeException unavailable){failed=true;busy=false;failure.run();}
    }
    public void close(){closed=true;}
}
