package ru.warland.content;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import ru.warland.api.WarLandApi;
import static org.junit.jupiter.api.Assertions.*;

class ContentStoreTest {
    static final class Fake {
        String initial;
        final List<String> writes=new ArrayList<>();
        final ArrayDeque<CompletableFuture<Void>> pending=new ArrayDeque<>();
        final WarLandApi api=(WarLandApi)Proxy.newProxyInstance(WarLandApi.class.getClassLoader(),new Class<?>[]{WarLandApi.class},(proxy,method,args)->switch(method.getName()) {
            case "getState"->CompletableFuture.completedFuture(initial);
            case "setState"->{writes.add((String)args[2]);var future=new CompletableFuture<Void>();pending.add(future);yield future;}
            case "toString"->"Fake content store";
            default->throw new AssertionError("Unexpected API use: "+method.getName());
        });
        ContentStore store() {return new ContentStore(api,(java.util.concurrent.Executor)Runnable::run);}
        void ack() {pending.remove().complete(null);}
    }
    @Test void loadIsNotAcknowledgedBeforeDurableWrite() {var f=new Fake();var s=f.store();AtomicInteger loaded=new AtomicInteger();s.load(loaded::incrementAndGet);assertEquals(0,loaded.get());assertFalse(s.idle());f.ack();assertEquals(1,loaded.get());assertTrue(s.idle());}
    @Test void serialUpdatesCannotLoseConcurrentClaims() {var f=new Fake();var s=f.store();s.load(()->{});f.ack();UUID id=UUID.randomUUID();s.change(d->d.profile(id).reputation+=12,()->{});s.change(d->d.profile(id).reputation+=18,()->{});assertEquals(2,f.writes.size());assertNull(s.data().profiles.get(id.toString()));f.ack();assertEquals(12,s.data().profiles.get(id.toString()).reputation);assertEquals(3,f.writes.size());f.ack();assertEquals(30,s.data().profiles.get(id.toString()).reputation);assertTrue(s.idle());}
    @Test void writeFailureDisablesFurtherWorldCallbacks() {var f=new Fake();var s=f.store();s.load(()->{});f.ack();AtomicInteger success=new AtomicInteger(),failure=new AtomicInteger();s.change(d->d.config.placementBudget=32,success::incrementAndGet,e->failure.incrementAndGet());s.change(d->d.config.placementBudget=16,success::incrementAndGet,e->failure.incrementAndGet());f.pending.remove().completeExceptionally(new IllegalStateException("Injected I/O failure"));assertFalse(s.ready());assertEquals(0,success.get());assertEquals(2,failure.get());assertEquals(64,s.lastSnapshot().config.placementBudget);}
    @Test void invalidMutationDoesNotWriteOrPoisonOtherRequests() {var f=new Fake();var s=f.store();s.load(()->{});f.ack();AtomicInteger errors=new AtomicInteger();s.change(d->d.config.depotCost=0,()->fail("Should not succeed"),e->errors.incrementAndGet());assertEquals(1,f.writes.size());assertEquals(1,errors.get());assertTrue(s.ready());assertEquals(600,s.data().config.depotCost);}
    @Test void shutdownNeverExecutesPendingWorldChange() {var f=new Fake();var s=f.store();s.load(()->{});f.ack();AtomicInteger ran=new AtomicInteger();s.change(d->d.config.placementBudget=32,ran::incrementAndGet);s.close();f.ack();assertEquals(0,ran.get());assertFalse(s.ready());}
    @Test void corruptOrFutureSchemaIsNotOverwritten() {var f=new Fake();f.initial="{\"schema\":999}";var s=f.store();s.load(()->fail("Should not load"));assertFalse(s.ready());assertTrue(f.writes.isEmpty());}
    @Test void interruptedJobsAreQuarantinedBeforeStartupCallback() {var f=new Fake();var d=new ContentData();var j=new ContentData.Job();j.id=UUID.randomUUID().toString();j.owner=UUID.randomUUID().toString();j.world="minecraft:overworld";j.plan="depot_v1";j.hash="test";j.price=600;j.paid=true;j.materialsHeld=true;j.stage="BATCH";j.y=80;j.batchEnd=64;d.jobs.put(j.id,j);f.initial=ContentStore.JSON.toJson(d);var s=f.store();AtomicInteger loaded=new AtomicInteger();s.load(()->{assertEquals("RECOVERY",s.data().jobs.get(j.id).stage);loaded.incrementAndGet();});assertEquals(0,loaded.get());f.ack();assertEquals(1,loaded.get());assertTrue(f.writes.getFirst().contains("RECOVERY"));}
}
