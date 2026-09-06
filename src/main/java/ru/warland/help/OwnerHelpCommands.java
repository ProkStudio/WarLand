package ru.warland.help;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Pure Brigadier adapter; receives authorization/reply callbacks, never mutation callbacks. */
public final class OwnerHelpCommands {
    private OwnerHelpCommands() {}
    public static <S> void install(CommandDispatcher<S> dispatcher, Predicate<S> owner, BiConsumer<S,String> reply) {
        var existing = dispatcher.getRoot().getChild("wladmin");
        if (existing == null || existing.getChild("help") != null) {
            throw new IllegalStateException("Owner help requires the existing wladmin root and an unused help child");
        }
        // Brigadier merges the handler/children and RETAINS the existing root requirement.
        // No gameplay child is replaced. Every new handler rechecks the current owner lease.
        var help = LiteralArgumentBuilder.<S>literal("help").requires(owner)
            .executes(c -> show(dispatcher, c.getSource(), owner, reply, 1))
            .then(RequiredArgumentBuilder.<S,Integer>argument("page", IntegerArgumentType.integer(1))
                .requires(owner)
                .executes(c -> show(dispatcher, c.getSource(), owner, reply, IntegerArgumentType.getInteger(c,"page"))));
        dispatcher.register(LiteralArgumentBuilder.<S>literal("wladmin").requires(owner)
            .executes(c -> show(dispatcher, c.getSource(), owner, reply, 1)).then(help));
    }
    static <S> boolean available(CommandDispatcher<S> dispatcher, S source, List<String> path) {
        CommandNode<S> node = dispatcher.getRoot();
        for (String part : path) {
            node = node.getChild(part);
            if (node == null || !node.canUse(source)) return false;
        }
        return true;
    }
    private static <S> int show(CommandDispatcher<S> dispatcher, S source, Predicate<S> owner,
                                BiConsumer<S,String> reply, int page) {
        if (!owner.test(source)) return 0;
        var visible = OwnerHelpCatalog.visible(path -> available(dispatcher, source, path));
        int pages = OwnerHelpCatalog.pages(visible);
        if (page < 1 || page > pages) {
            reply.accept(source, "[WarLand] Страница справки: 1–" + pages + ". /wladmin help 1");
            return 0;
        }
        reply.accept(source, "[WarLand] Команды владельца · " + page + "/" + pages + " · только доступные узлы текущей сборки");
        reply.accept(source, "Только справка: примеры НЕ выполняются. <...> замените проверенными значениями; TestPlayer — пример, не цель.");
        for (var entry : OwnerHelpCatalog.page(visible, page)) {
            reply.accept(source, entry.usage() + " — " + entry.description());
            reply.accept(source, "Пример: " + entry.example());
        }
        reply.accept(source, "Навигация: /wladmin help <1–" + pages + ">. Обычные игровые команды: /help. OWNER ≠ OP; консольный bootstrap не команда игрока.");
        return 1;
    }
}
