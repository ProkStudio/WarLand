package ru.warland.help;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.text.Text;
import ru.warland.core.CoreRuntime;

/** Registered after CoreRuntime, preserving its existing owner-only root guard. */
public final class OwnerHelp {
    private OwnerHelp() {}
    public static void register(CoreRuntime runtime) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            OwnerHelpCommands.install(dispatcher, source -> runtime.staff(source, "owner"),
                (source, line) -> source.sendFeedback(() -> Text.literal(line), false)));
    }
}
