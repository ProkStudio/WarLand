package ru.warland.vehicles;

import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import ru.warland.vehicles.VehicleState.*;
import static org.junit.jupiter.api.Assertions.*;

class VehicleRegressionTest {
 static final UUID OWNER=UUID.fromString("00000000-0000-0000-0000-000000000001");
 static Vehicle vehicle(Status status){return new Vehicle(UUID.randomUUID(),OWNER,status,1000,status==Status.DESTROYED?0:100,"minecraft:overworld",0,64,0,0);}
 @Test void forwardAndReverseSpeedAreBounded(){double s=0;for(int i=0;i<10000;i++)s=VehicleRules.speed(s,1);assertEquals(VehicleRules.MAX_SPEED,s);for(int i=0;i<10000;i++)s=VehicleRules.speed(s,-1);assertEquals(-VehicleRules.MAX_SPEED/2,s);}
 @Test void brakingStopsAndInvalidInputsFail(){double s=.18;for(int i=0;i<100;i++)s=VehicleRules.speed(s,0);assertEquals(0,s);assertThrows(IllegalArgumentException.class,()->VehicleRules.speed(Double.NaN,1));assertThrows(IllegalArgumentException.class,()->VehicleRules.heading(0,2));}
 @Test void headingIsNormalized(){assertEquals(2,VehicleRules.heading(358,1));assertEquals(358,VehicleRules.heading(2,-1));}
 @Test void fuelLeaseNeverExceedsAvailable(){assertEquals(0,VehicleRules.reserveFuel(0));assertEquals(30,VehicleRules.reserveFuel(30));assertEquals(100,VehicleRules.reserveFuel(6000));assertThrows(IllegalArgumentException.class,()->VehicleRules.reserveFuel(-1));}
 @Test void destructionIsIrreversible(){var dead=vehicle(Status.DEPLOYED).damage(100);assertEquals(Status.DESTROYED,dead.status());assertEquals(0,dead.health());assertThrows(IllegalStateException.class,()->dead.status(Status.PARKED));}
 @Test void fleetMapIsImmutableAndIdsMustMatch(){var v=vehicle(Status.OWNED);var fleet=Fleet.empty().put(v);assertThrows(UnsupportedOperationException.class,()->fleet.vehicles().clear());assertThrows(IllegalArgumentException.class,()->new Fleet(1,0,Map.of(UUID.randomUUID(),v)));}
 @Test void recoveryQuarantinesDeploymentWithoutRestoringFuel(){var v=vehicle(Status.DEPLOYED);var recovered=Fleet.empty().put(v).recoverInterrupted().vehicles().get(v.id());assertEquals(Status.RECOVERY,recovered.status());assertEquals(v.fuel(),recovered.fuel());assertEquals(v.health(),recovered.health());}
 @Test void revisionOverflowFailsClosed(){assertThrows(ArithmeticException.class,()->new Fleet(1,Long.MAX_VALUE,Map.of()).put(vehicle(Status.OWNED)));}
 static final class Harness {
  final List<CompletableFuture<Void>> writes=new ArrayList<>();final List<String> documents=new ArrayList<>();final VehicleStore store;
  Harness(String initial){store=new VehicleStore(()->CompletableFuture.completedFuture(initial),json->{documents.add(json);var pending=new CompletableFuture<Void>();writes.add(pending);return pending;},Runnable::run);}
  void load(){store.load();writes.getLast().complete(null);assertTrue(store.ready());}
 }
 @Test void startupWaitsForDurableRecoveryAcknowledgement(){var h=new Harness(null);h.store.load();assertFalse(h.store.ready());h.writes.getFirst().complete(null);assertTrue(h.store.ready());}
 @Test void stateDoesNotChangeUntilAcknowledgedAndBusyRejectsSecondWriter(){var h=new Harness(null);h.load();var v=vehicle(Status.OWNED);assertTrue(h.store.change(f->f.put(v),()->{},()->fail()));assertTrue(h.store.snapshot().vehicles().isEmpty());assertFalse(h.store.change(f->f,()->fail(),()->fail()));h.writes.getLast().complete(null);assertEquals(v,h.store.snapshot().vehicles().get(v.id()));}
 @Test void failedWriteLocksAndKeepsPublishedSnapshot(){var h=new Harness(null);h.load();var failures=new int[1];h.store.change(f->f.put(vehicle(Status.OWNED)),()->fail(),()->failures[0]++);h.writes.getLast().completeExceptionally(new IllegalStateException("I/O"));assertFalse(h.store.ready());assertTrue(h.store.snapshot().vehicles().isEmpty());assertEquals(1,failures[0]);}
 @Test void restartLoadsPersistedVehicleIntoRecovery(){var v=vehicle(Status.DEPLOYED);var h=new Harness(new Gson().toJson(Fleet.empty().put(v)));h.load();assertEquals(Status.RECOVERY,h.store.snapshot().vehicles().get(v.id()).status());assertEquals(1000,h.store.snapshot().vehicles().get(v.id()).fuel());}
 @Test void closedStoreCannotPublishLateCompletion(){var h=new Harness(null);h.load();h.store.change(f->f.put(vehicle(Status.OWNED)),()->fail(),()->fail());h.store.close();h.writes.getLast().complete(null);assertFalse(h.store.ready());assertTrue(h.store.snapshot().vehicles().isEmpty());}
 @Test void malformedFleetNeverBecomesReady(){for(String raw:List.of("","null","{}","{\"schema\":2,\"revision\":0,\"vehicles\":{}}")){var h=new Harness(raw);h.store.load();assertFalse(h.store.ready());assertTrue(h.writes.isEmpty());}}
}
