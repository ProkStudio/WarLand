package ru.warland.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationOrderTest {
    private Event<Runnable> event() {
        return EventFactory.createArrayBacked(Runnable.class, callbacks -> () -> {
            for (Runnable callback : callbacks) callback.run();
        });
    }

    @Test void authPrecedesAlreadyRegisteredFabricTasks() {
        List<String> tasks = new ArrayList<>();
        Event<Runnable> event = event();
        event.register(() -> tasks.add("fabric:registry/sync"));
        ConfigurationOrder.beforeDefaults(event, () -> tasks.add("warland:authentication"));
        event.invoker().run();
        assertEquals(List.of("warland:authentication", "fabric:registry/sync"), tasks);
    }

    @Test void authPrecedesLaterDefaultTasksAndDoesNotReorderThem() {
        List<String> tasks = new ArrayList<>();
        Event<Runnable> event = event();
        ConfigurationOrder.beforeDefaults(event, () -> tasks.add("auth"));
        event.register(() -> tasks.add("first"));
        event.register(() -> tasks.add("second"));
        event.invoker().run();
        event.invoker().run();
        assertEquals(List.of("auth", "first", "second", "auth", "first", "second"), tasks);
    }

    @Test void runtimeUsesOrderedRegistrationWithoutAllowlistingRegistryBypass() throws Exception {
        String runtime = Files.readString(Path.of("src/main/java/ru/warland/auth/AuthRuntime.java"));
        assertTrue(runtime.contains("ConfigurationOrder.beforeDefaults(ServerConfigurationConnectionEvents.BEFORE_CONFIGURE, this::configure)"));
        assertFalse(runtime.contains("BEFORE_CONFIGURE.register(this::configure)"));
        String gate = Files.readString(Path.of("src/main/java/ru/warland/auth/AuthPacketGate.java"));
        assertFalse(gate.contains("registry/sync/complete"));
    }
}
