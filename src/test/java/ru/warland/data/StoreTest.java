package ru.warland.data;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
 @TempDir Path dir; Store store; UUID id=UUID.randomUUID();
 @BeforeEach void open() throws Exception{store=new Store(dir.resolve("test.db"));store.start().get(10,TimeUnit.SECONDS);}
 @AfterEach void close(){store.close();}
 @Test void idempotentAndNoNegative()throws Exception{assertTrue(store.money(id,100,"grant","test").get());assertTrue(store.money(id,100,"grant","test").get());assertEquals(100,store.balance(id).get());assertFalse(store.money(id,-101,"spend","test").get());assertEquals(100,store.balance(id).get());assertTrue(store.money(id,-60,"buy","test").get());assertEquals(40,store.balance(id).get());}
 @Test void conflictingReuseRejected()throws Exception{store.money(id,10,"same","test").get();assertThrows(ExecutionException.class,()->store.money(id,11,"same","test").get());assertEquals(10,store.balance(id).get());}
 @Test void transactionRollsBack()throws Exception{store.money(id,100,"start","test").get();assertThrows(ExecutionException.class,()->store.tx(c->{Store.change(c,Store.player(id),-90,"t","test");throw new IllegalStateException("simulated crash");}).get());assertEquals(100,store.balance(id).get());}
 @Test void concurrentSpendingSerialized()throws Exception{store.money(id,100,"start","test").get();var jobs=new ArrayList<CompletableFuture<Boolean>>();for(int i=0;i<100;i++)jobs.add(store.money(id,-2,"buy"+i,"test"));CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get();assertEquals(50,jobs.stream().filter(CompletableFuture::join).count());assertEquals(0,store.balance(id).get());}
 @Test void restartPersists()throws Exception{store.money(id,999,"start","test").get();store.close();store=new Store(dir.resolve("test.db"));store.start().get();assertEquals(999,store.balance(id).get());}
 @Test void ledgerImmutable()throws Exception{store.money(id,1,"one","test").get();assertThrows(ExecutionException.class,()->store.tx(c->Store.update(c,"DELETE FROM ledger")).get());}
 @Test void stateIsNamespaced()throws Exception{store.state("a","same","one").get();store.state("b","same","two").get();assertEquals("one",store.state("a","same").get());assertEquals("two",store.state("b","same").get());}
}
