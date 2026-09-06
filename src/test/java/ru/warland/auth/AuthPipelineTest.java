package ru.warland.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AuthPipelineTest {
    @TempDir Path dir;
    @Test void closeRejectsDatabaseWaitersAndClearsTheirPasswordsImmediately() throws Exception { blockedPipeline(false); }
    @Test void cancellationCannotRecyclePermitsWhileDatabaseWorkRemainsQueued() throws Exception { blockedPipeline(true); }
    private void blockedPipeline(boolean cancel) throws Exception {
        try (Store store = new Store(dir.resolve("auth.db"))) {
            store.start().get(); AuthRepository repo = new AuthRepository(store); repo.start().get();
            try (Authentication auth = new Authentication(repo)) {
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var block = store.submit(c -> { entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); return null; });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    List<CompletableFuture<Authentication.Result>> calls = new ArrayList<>();
                    for (int i = 0; i < 9; i++) calls.add(auth.login(auth.open(UUID.randomUUID(), "User" + i, "peer" + i), "synthetic pipeline passphrase".toCharArray()));
                    assertTrue(calls.stream().noneMatch(CompletableFuture::isDone));
                    assertEquals(Authentication.Result.DENIED, auth.login(auth.open(UUID.randomUUID(), "OverLimit", "otherpeer"), "synthetic pipeline passphrase".toCharArray()).get(1, TimeUnit.SECONDS));
                    var field = Authentication.class.getDeclaredField("requests"); field.setAccessible(true);
                    Set<?> requests = (Set<?>) field.get(auth); assertEquals(9, requests.size());
                    List<char[]> secrets = new ArrayList<>();
                    for (Object request : requests) { var secret = request.getClass().getDeclaredField("secret"); secret.setAccessible(true); secrets.add((char[]) secret.get(request)); }
                    if (cancel) {
                        for (var future : calls) assertTrue(future.cancel(false));
                        assertEquals(Authentication.Result.DENIED, auth.login(auth.open(UUID.randomUUID(), "StillBound", "otherpeer"), "synthetic pipeline passphrase".toCharArray()).get(1, TimeUnit.SECONDS));
                        assertEquals(9, requests.size());
                    } else {
                        auth.close();
                        for (var future : calls) assertEquals(Authentication.Result.DENIED, future.get(1, TimeUnit.SECONDS));
                    }
                    for (char[] secret : secrets) assertArrayEquals(new char[secret.length], secret);
                    release.countDown(); block.get(5, TimeUnit.SECONDS);
                    store.submit(c -> null).get(5, TimeUnit.SECONDS); // queued lookups and their rejection callbacks drained
                    assertTrue(requests.isEmpty());
                } finally { release.countDown(); }
            }
        }
    }
}
