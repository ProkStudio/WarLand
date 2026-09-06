package ru.warland.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class AuthCancellationTest {
    @TempDir Path dir;
    @Test void cancellingExposedFutureCannotSuppressPasswordCleanup() throws Exception { verifyCleanup(true); }
    @Test void exceptionalCompletionAlsoClearsOwnedSecret() throws Exception { verifyCleanup(false); }
    @SuppressWarnings("unchecked")
    private void verifyCleanup(boolean cancel) throws Exception {
        try (Store store = new Store(dir.resolve("unused.db")); Authentication auth = new Authentication(new AuthRepository(store))) {
            var internal = new CompletableFuture<Authentication.Result>();
            var captured = new AtomicReference<char[]>();
            Function<char[], CompletableFuture<Authentication.Result>> operation = secret -> { captured.set(secret); return internal; };
            var consume = Authentication.class.getDeclaredMethod("consume", char[].class, Function.class);
            consume.setAccessible(true);
            char[] input = "synthetic cancellation passphrase".toCharArray();
            var exposed = (CompletableFuture<Authentication.Result>) consume.invoke(auth, input, operation);
            assertArrayEquals(new char[input.length], input);
            assertNotEquals('\0', captured.get()[0]);
            if (cancel) {
                assertTrue(exposed.cancel(false)); internal.complete(Authentication.Result.SUCCESS);
                assertTrue(exposed.isCancelled());
            } else {
                internal.completeExceptionally(new IllegalStateException("synthetic failure"));
                assertEquals(Authentication.Result.DENIED, exposed.join());
            }
            assertArrayEquals(new char[captured.get().length], captured.get());
        }
    }
}
