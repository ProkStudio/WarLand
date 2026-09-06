package ru.warland.moderation;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModerationPublicationTest {
 @Test void neverPublishesBeforeAcknowledgement(){
  var write=new CompletableFuture<Void>();var visible=new AtomicReference<>("old");
  var result=ModerationPublication.publish(write,Runnable::run,()->visible.set("new"),e->fail(e));
  assertEquals("old",visible.get());assertFalse(result.isDone());
  write.complete(null);result.join();assertEquals("new",visible.get());
 }
 @Test void failureRetainsPreviousSnapshotAndSignalsLock(){
  var write=new CompletableFuture<Void>();var visible=new AtomicReference<>("old");var locked=new AtomicBoolean();
  var result=ModerationPublication.publish(write,Runnable::run,()->visible.set("new"),e->locked.set(true));
  write.completeExceptionally(new IllegalStateException("injected I/O"));result.join();
  assertEquals("old",visible.get());assertTrue(locked.get());
 }
 @Test void acknowledgementStillWaitsForOwningThread(){
  var queue=new ArrayList<Runnable>();var published=new AtomicBoolean();
  var result=ModerationPublication.publish(CompletableFuture.completedFuture(null),queue::add,()->published.set(true),e->fail(e));
  assertFalse(published.get());assertFalse(result.isDone());assertEquals(1,queue.size());
  queue.removeFirst().run();result.join();assertTrue(published.get());
 }
 @Test void repeatedAcknowledgementCannotPublishTwice(){
  var write=new CompletableFuture<Void>();var count=new AtomicInteger();
  var result=ModerationPublication.publish(write,Runnable::run,count::incrementAndGet,e->fail(e));
  assertTrue(write.complete(null));assertFalse(write.complete(null));result.join();assertEquals(1,count.get());
 }
 @Test void rejectedOwningThreadDoesNotPublish(){
  var write=new CompletableFuture<Void>();var published=new AtomicBoolean();
  var result=ModerationPublication.publish(write,r->{throw new RejectedExecutionException();},()->published.set(true),e->fail(e));
  write.complete(null);assertThrows(CompletionException.class,result::join);assertFalse(published.get());
 }
}
