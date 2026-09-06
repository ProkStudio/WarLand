package ru.warland.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import ru.warland.data.Store;
import static org.junit.jupiter.api.Assertions.*;

class AuthBoundaryTest {
    @TempDir Path dir;
    @Test void invalidPasswordIsClearedBeforeAnyDatabaseWork() {
        try (Store store = new Store(dir.resolve("unused.db")); Authentication auth = new Authentication(new AuthRepository(store))) {
            var c = auth.open(UUID.randomUUID(), "TestUser", "loopback");
            char[] tooLong = new char[129]; java.util.Arrays.fill(tooLong, 'x');
            assertEquals(Authentication.Result.DENIED, auth.register(c, tooLong, null, 1000).join());
            assertArrayEquals(new char[129], tooLong);
            assertEquals(Authentication.Result.DENIED, auth.login(c, null).join());
            char[] shortValue = "short".toCharArray();
            assertEquals(Authentication.Result.DENIED, auth.login(c, shortValue).join());
            assertArrayEquals(new char[5], shortValue);
        }
    }
    @Test void closedEngineCannotAuthenticateAndStillClearsInput() {
        try (Store store = new Store(dir.resolve("unused.db")); Authentication auth = new Authentication(new AuthRepository(store))) {
            var c = auth.open(UUID.randomUUID(), "TestUser", "loopback"); auth.close();
            char[] password = "synthetic passphrase".toCharArray();
            assertEquals(Authentication.Result.DENIED, auth.login(c, password).join());
            assertArrayEquals(new char[password.length], password); assertFalse(auth.authenticated(c));
        }
    }
}
