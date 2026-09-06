package ru.warland.moderation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Publication is ordered after durable acknowledgement and onto the owning thread. */
final class ModerationPublication {
 private ModerationPublication() {}
 static CompletableFuture<Void> publish(CompletableFuture<Void> write,Executor executor,Runnable publish,Consumer<Throwable> failed){
  return write.handleAsync((unused,error)->{if(error==null)publish.run();else failed.accept(error);return null;},executor);
 }
}
