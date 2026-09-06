package ru.warland.auth;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.util.Identifier;

/** Queue authentication before Fabric registry sync, independent of mod load order. */
final class ConfigurationOrder {
    private static final Identifier AUTH_PHASE = Identifier.of("warland", "authentication");
    private ConfigurationOrder() {}

    static <T> void beforeDefaults(Event<T> event, T listener) {
        event.addPhaseOrdering(AUTH_PHASE, Event.DEFAULT_PHASE);
        event.register(AUTH_PHASE, listener);
    }
}
