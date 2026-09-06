package ru.warland.rtp;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Bootstrap source contract; real Fabric startup remains a separate acceptance gate. */
class RtpRegistrationTest {
    @Test void registersExactlyOnceInDedicatedServerInitializer() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/warland/WarLand.java"));
        String call = "RtpService.register(runtime);";
        int start = source.indexOf("public void onInitialize()");
        int registration = source.indexOf(call);
        int runtime = source.indexOf("runtime.initialize();", start);
        int clientGuard = source.indexOf("EnvType.CLIENT)return;", start);
        assertTrue(start >= 0 && clientGuard > start && runtime > clientGuard && registration > runtime);
        assertEquals(registration, source.lastIndexOf(call));
        String core = Files.readString(Path.of("src/main/java/ru/warland/core/CoreRuntime.java"));
        assertFalse(core.contains("RtpService.register("), "Registration belongs to the dedicated mod initializer");
    }
}
