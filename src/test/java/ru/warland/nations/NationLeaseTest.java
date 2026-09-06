package ru.warland.nations;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.core.GameConfig;
import ru.warland.data.Store;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class NationLeaseTest {
 @TempDir Path dir;
 Store db;NationsService nations;
 UUID leader=UUID.randomUUID(),member=UUID.randomUUID(),outsider=UUID.randomUUID();
 AtomicInteger generation=new AtomicInteger(1),checks=new AtomicInteger();
 AtomicBoolean alive=new AtomicBoolean(true);
 @BeforeEach void setup()throws Exception{
  db=new Store(dir.resolve("test.db"));await(db.start());
  await(db.tx(c->{
   Store.update(c,"INSERT INTO nations(id,name,owner,cx,cy,cz,dimension,created) VALUES('a','Alpha',?,0,70,0,'minecraft:overworld',1)",leader.toString());
   Store.update(c,"INSERT INTO nations(id,name,owner,cx,cy,cz,dimension,created) VALUES('b','Bravo',?,32,70,0,'minecraft:overworld',1)",UUID.randomUUID().toString());
   Store.update(c,"INSERT INTO members(uuid,nation,rank,joined) VALUES(?,'a','LEADER',1)",leader.toString());
   Store.update(c,"INSERT INTO members(uuid,nation,rank,joined) VALUES(?,'a','MEMBER',1)",member.toString());
   Store.update(c,"INSERT INTO claims(dimension,x,z,nation,created) VALUES('minecraft:overworld',0,0,'a',1)");
   for(UUID id:List.of(leader,member,outsider)){
    Store.update(c,"INSERT INTO profiles(uuid,name,joined,last_seen,tutorial) VALUES(?,?,1,?,1)",id.toString(),"Test"+id,System.currentTimeMillis());
    Store.change(c,Store.player(id),20000,"seed:"+id,"test");
   }
   Store.change(c,Store.nation("a"),50000,"seed:nation","test");return null;
  }));
  nations=new NationsService(db,new GameConfig(),actor->{
   int captured=generation.get();boolean admitted=alive.get();
   return ()->{checks.incrementAndGet();return admitted&&alive.get()&&generation.get()==captured;};
  });
  await(nations.refresh());
 }
 @AfterEach void close(){if(db!=null)db.close();}
 static <T>T await(CompletableFuture<T> f)throws Exception{return f.get(8,TimeUnit.SECONDS);}
 static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
 void denied(CompletableFuture<?> f){assertInstanceOf(CancellationException.class,root(assertThrows(Exception.class,()->await(f))));}
 record Block(CountDownLatch release,CompletableFuture<Void> future)implements AutoCloseable{
  public void close(){release.countDown();}
 }
 Block block()throws Exception{
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  CompletableFuture<Void> f=db.submit(c->{entered.countDown();if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("test queue timeout");return null;});
  assertTrue(entered.await(8,TimeUnit.SECONDS));return new Block(release,f);
 }
 List<Long> snapshot()throws Exception{return await(db.submit(c->List.of(
  Store.scalar(c,"SELECT COUNT(*) FROM nations"),Store.scalar(c,"SELECT COUNT(*) FROM members"),
  Store.scalar(c,"SELECT COUNT(*) FROM claims"),Store.scalar(c,"SELECT COUNT(*) FROM invites"),
  Store.scalar(c,"SELECT COUNT(*) FROM diplomacy"),Store.scalar(c,"SELECT COUNT(*) FROM state"),
  Store.scalar(c,"SELECT COUNT(*) FROM ledger"),Store.scalar(c,"SELECT SUM(balance) FROM accounts"),
  Store.scalar(c,"SELECT COUNT(*) FROM nations WHERE pending_tax IS NOT NULL"))));}

 @Test void everyNationMutationChecksItsCapturedSessionAfterQueueing()throws Exception{
  var before=snapshot();List<CompletableFuture<String>> queued;
  try(Block held=block()){
   queued=List.of(nations.create(outsider,"Fresh","minecraft:overworld",256,70,256),
    nations.claim(leader,"minecraft:overworld",1,0),nations.invite(leader,outsider),
    nations.join(outsider,"Alpha"),nations.leave(member),nations.transfer(leader,100,false),
    nations.transfer(leader,100,true),nations.tax(leader,5),nations.rank(leader,member,"OFFICER"),
    nations.defineRank(leader,"VISITOR","build"),nations.pact(leader,"Bravo"));
   alive.set(false);
  }
  for(var action:queued)denied(action);
  assertEquals(11,checks.get());assertEquals(before,snapshot());assertEquals("MEMBER",nations.member(member).rank());
 }
 @Test void sameUuidReconnectCannotReviveOldQueuedAction()throws Exception{
  CompletableFuture<String> old;
  try(Block held=block()){old=nations.rank(leader,member,"OFFICER");generation.incrementAndGet();}
  denied(old);assertEquals("MEMBER",nations.member(member).rank());
  await(nations.rank(leader,member,"OFFICER"));assertEquals("OFFICER",nations.member(member).rank());
 }
 @Test void pendingCaptureStaysDeniedAfterLaterAuthentication()throws Exception{
  CompletableFuture<String> old;
  alive.set(false);
  try(Block held=block()){old=nations.rank(leader,member,"OFFICER");alive.set(true);}
  denied(old);assertEquals("MEMBER",nations.member(member).rank());
 }
 @Test void cancellingObserverCannotSuppressCommittedCachePublication()throws Exception{
  CompletableFuture<String> observer;
  try(Block held=block()){observer=nations.rank(leader,member,"OFFICER");assertTrue(observer.cancel(false));}
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
  while(!"OFFICER".equals(nations.member(member).rank())&&System.nanoTime()<deadline)Thread.sleep(5);
  assertTrue(observer.isCancelled());assertEquals("OFFICER",nations.member(member).rank());
  assertEquals("OFFICER",await(db.submit(c->Store.string(c,"SELECT rank FROM members WHERE uuid=?",member.toString()))));
 }
 @Test void postAdmissionRevocationStillPublishesDurableState()throws Exception{
  NationsService atAdmission=new NationsService(db,new GameConfig(),id->()->{
   boolean permitted=alive.get();alive.set(false);return permitted;
  });
  await(atAdmission.rank(leader,member,"OFFICER"));
  assertFalse(alive.get());assertEquals("OFFICER",atAdmission.member(member).rank());
 }
 @Test void validSessionDoesNotBypassDurableTreasuryPermission()throws Exception{
  var before=snapshot();
  assertInstanceOf(SQLException.class,root(assertThrows(Exception.class,()->await(nations.transfer(member,100,true)))));
  assertEquals(before,snapshot());
 }
 @Test void systemTaxTickDoesNotRequireAnOnlineActor()throws Exception{
  alive.set(false);await(db.tx(c->Store.update(c,"UPDATE nations SET pending_tax=7,effective_tax=1 WHERE id='a'")));
  await(nations.applyTaxes());assertEquals(7,nations.snapshot().nations().get("a").tax());assertEquals(0,checks.get());
 }
 @Test void productionWiringCapturesOnlyOnServerThread()throws Exception{
  String core=Files.readString(Path.of("src/main/java/ru/warland/core/CoreRuntime.java"));
  assertTrue(core.contains("new NationsService(store,config,this::actionLease)"));
  assertTrue(core.contains("!server.isOnThread()"));
  assertTrue(core.contains("Store.grantStarter(c,id,config.startingBalance)"));
  String auth=Files.readString(Path.of("src/main/java/ru/warland/auth/AuthRuntime.java"));
  String body=auth.substring(auth.indexOf("public BooleanSupplier lease("),auth.indexOf("public void profileReady("));
  assertTrue(body.contains("if (!allowed(player)) return () -> false"));
  assertTrue(body.contains("sessions.get(c) == s"));assertTrue(body.contains("s.play == play"));
  assertTrue(body.contains("s.profileReady && engine.authenticated(s.identity)"));
  assertFalse(body.substring(body.indexOf("return () -> !closed")).contains("player."));
 }
}
