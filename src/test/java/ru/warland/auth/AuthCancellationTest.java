package ru.warland.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AuthCancellationTest {
    @TempDir Path dir;
    @Test void cancellingExposedFutureCannotSuppressPasswordCleanup() throws Exception { verifyCleanup(true); }
    @Test void exceptionalCompletionAlsoClearsOwnedSecret() throws Exception { verifyCleanup(false); }
    private void verifyCleanup(boolean cancel) throws Exception {
        try (Store store = new Store(dir.resolve("auth.db"))) {
            store.start().get(); AuthRepository repo = new AuthRepository(store); repo.start().get();
            try (Authentication auth = new Authentication(repo)) {
                var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                var block = store.submit(c -> { entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); if (!cancel) Store.update(c, "DROP TABLE auth_accounts"); return null; });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    char[] input = "synthetic cancellation passphrase".toCharArray();
                    var exposed = auth.login(auth.open(UUID.randomUUID(), "TestUser", "loopback"), input);
                    var field = Authentication.class.getDeclaredField("requests"); field.setAccessible(true);
                    Object request = ((Set<?>) field.get(auth)).iterator().next();
                    var secretField = request.getClass().getDeclaredField("secret"); secretField.setAccessible(true);
                    char[] owned = (char[]) secretField.get(request);
                    assertArrayEquals(new char[input.length], input); assertNotEquals('\0', owned[0]);
                    if (cancel) { assertTrue(exposed.cancel(false)); assertArrayEquals(new char[owned.length], owned); }
                    release.countDown(); block.get(5, TimeUnit.SECONDS);
                    if (cancel) assertTrue(exposed.isCancelled());
                    else assertEquals(Authentication.Result.DENIED, exposed.get(5, TimeUnit.SECONDS));
                    assertArrayEquals(new char[owned.length], owned);
                } finally { release.countDown(); }
            }
        }
    }
}
