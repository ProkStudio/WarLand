package ru.warland.data;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class StarterAndLeaseTest {
 @TempDir Path dir;
 Store db;
 UUID player=UUID.randomUUID();
 @BeforeEach void open()throws Exception{db=new Store(dir.resolve("test.db"));db.start().get(5,TimeUnit.SECONDS);}
 @AfterEach void close(){if(db!=null)db.close();}
 <T>T await(CompletableFuture<T> f)throws Exception{return f.get(5,TimeUnit.SECONDS);}
 boolean grant(UUID id,long amount)throws Exception{return await(db.tx(c->Store.grantStarter(c,id,amount)));}
 long scalar(String sql,Object...args)throws Exception{return await(db.submit(c->Store.scalar(c,sql,args)));}
 static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}

 @Test void changedConfigAfterRestartDoesNotRepeatOrRepriceGrant()throws Exception{
  assertTrue(grant(player,1500));
  assertTrue(await(db.money(player,-300,"spend","test")));
  db.close();db=null;open();
  assertTrue(grant(player,9000));
  assertEquals(1200,await(db.balance(player)).longValue());
  assertEquals(1,scalar("SELECT COUNT(*) FROM ledger WHERE operation=?","starter:"+player));
  assertEquals(1500,scalar("SELECT delta FROM ledger WHERE operation=?","starter:"+player));
 }
 @Test void newPlayerUsesNewConfiguredAmount()throws Exception{
  UUID other=UUID.randomUUID();assertTrue(grant(player,1500));assertTrue(grant(other,9000));
  assertEquals(1500,await(db.balance(player)).longValue());assertEquals(9000,await(db.balance(other)).longValue());
 }
 @Test void zeroIsARealHistoricalGrant()throws Exception{
  assertTrue(grant(player,0));assertTrue(grant(player,1500));assertEquals(0,await(db.balance(player)).longValue());
  assertEquals(1,scalar("SELECT COUNT(*) FROM ledger"));
 }
 @Test void invalidAmountsCannotCreateRecords(){
  for(long amount:new long[]{-1,1_000_000_000_001L,Long.MAX_VALUE})
   assertInstanceOf(SQLException.class,root(assertThrows(Exception.class,()->grant(player,amount))));
 }
 @Test void wrongReasonIsNotMistakenForStarterReceipt()throws Exception{
  await(db.money(player,20,"starter:"+player,"not-starter"));
  assertInstanceOf(SQLException.class,root(assertThrows(Exception.class,()->grant(player,9000))));
  assertEquals(20,await(db.balance(player)).longValue());assertEquals(1,scalar("SELECT COUNT(*) FROM ledger"));
 }
 @Test void wrongOwnerReceiptFailsClosed()throws Exception{
  UUID other=UUID.randomUUID();await(db.money(other,20,"starter:"+player,"starter-grant"));
  assertInstanceOf(SQLException.class,root(assertThrows(Exception.class,()->grant(player,9000))));
  assertEquals(0,scalar("SELECT COUNT(*) FROM accounts WHERE owner=?",Store.player(player)));
 }
 @Test void missingHistoricalWalletIsNotRecreated()throws Exception{
  assertTrue(grant(player,1500));await(db.tx(c->Store.update(c,"DELETE FROM accounts WHERE owner=?",Store.player(player))));
  assertInstanceOf(SQLException.class,root(assertThrows(Exception.class,()->grant(player,9000))));
  assertEquals(0,scalar("SELECT COUNT(*) FROM accounts"));
 }
 @Test void corruptHistoricalDeltaIsRejected()throws Exception{
  for(long delta:new long[]{-1,1_000_000_000_001L}){
   UUID id=UUID.randomUUID();
   await(db.tx(c->{Store.account(c,Store.player(id));return Store.update(c,"INSERT INTO ledger(owner,delta,operation,reason,created) VALUES(?,?,?,'starter-grant',1)",Store.player(id),delta,"starter:"+id);}));
   assertInstanceOf(SQLException.class,root(assertThrows(Exception.class,()->grant(id,1500))));
   assertEquals(0,await(db.balance(id)).longValue());
  }
 }
 @Test void duplicateStarterOperationAcrossOwnersFailsClosed()throws Exception{
  assertTrue(grant(player,1500));UUID other=UUID.randomUUID();
  assertTrue(await(db.money(other,20,"starter:"+player,"starter-grant")));
  assertInstanceOf(SQLException.class,root(assertThrows(Exception.class,()->grant(player,9000))));
  assertEquals(1500,await(db.balance(player)).longValue());
  assertEquals(2,scalar("SELECT COUNT(*) FROM ledger WHERE operation=?","starter:"+player));
 }
 @Test void genericIdempotencyStillRejectsDifferentDelta()throws Exception{
  assertTrue(await(db.money(player,20,"generic","test")));
  assertInstanceOf(SQLException.class,root(assertThrows(Exception.class,()->await(db.money(player,21,"generic","test")))));
  assertEquals(20,await(db.balance(player)).longValue());
 }
 @Test void profileAndGrantRollbackTogether()throws Exception{
  assertThrows(Exception.class,()->await(db.tx(c->{
   Store.update(c,"INSERT INTO profiles(uuid,name,joined,last_seen) VALUES(?,?,1,1)",player.toString(),"Test");
   assertTrue(Store.grantStarter(c,player,1500));throw new SQLException("synthetic post-grant failure");
  })));
  assertEquals(0,scalar("SELECT COUNT(*) FROM profiles"));assertEquals(0,scalar("SELECT COUNT(*) FROM accounts"));
  assertEquals(0,scalar("SELECT COUNT(*) FROM ledger"));
 }
 @Test void overflowDoesNotCreateStarterReceipt()throws Exception{
  await(db.money(player,1_000_000_000_000L,"seed","test"));assertFalse(grant(player,1));
  assertEquals(0,scalar("SELECT COUNT(*) FROM ledger WHERE operation=?","starter:"+player));
  assertEquals(1_000_000_000_000L,await(db.balance(player)).longValue());
 }
 @Test void queuedStartersRemainExactlyOnce()throws Exception{
  var first=db.tx(c->Store.grantStarter(c,player,1500));
  var second=db.tx(c->Store.grantStarter(c,player,9000));
  assertTrue(await(first));assertTrue(await(second));assertEquals(1500,await(db.balance(player)).longValue());
  assertEquals(1,scalar("SELECT COUNT(*) FROM ledger"));
 }
 @Test void revokedQueuedLeaseNeverRunsTransaction()throws Exception{
  AtomicBoolean valid=new AtomicBoolean(true),ran=new AtomicBoolean();
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  var blocker=db.submit(c->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();return null;});
  assertTrue(entered.await(5,TimeUnit.SECONDS));
  var action=db.tx(valid::get,c->{ran.set(true);return Store.change(c,Store.player(player),100,"queued","test");});
  valid.set(false);release.countDown();await(blocker);
  assertInstanceOf(CancellationException.class,root(assertThrows(Exception.class,()->await(action))));
  assertFalse(ran.get());assertEquals(0,scalar("SELECT COUNT(*) FROM ledger"));
 }
 @Test void admittedCommitIsNotDiscardedByLaterRevocation()throws Exception{
  AtomicBoolean valid=new AtomicBoolean(true);
  boolean committed=await(db.tx(valid::get,c->{valid.set(false);return Store.change(c,Store.player(player),100,"admitted","test");}));
  assertTrue(committed);
  assertEquals(100,await(db.balance(player)).longValue());assertFalse(valid.get());
 }
 @Test void throwingLeaseCannotWriteAndDoesNotPoisonWorker()throws Exception{
  assertThrows(Exception.class,()->await(db.tx(()->{throw new IllegalStateException("synthetic lease failure");},c->Store.change(c,Store.player(player),100,"denied","test"))));
  assertEquals(0,scalar("SELECT COUNT(*) FROM ledger"));
  assertTrue(await(db.money(player,7,"system","offline-system")));assertEquals(7,await(db.balance(player)).longValue());
 }
}
