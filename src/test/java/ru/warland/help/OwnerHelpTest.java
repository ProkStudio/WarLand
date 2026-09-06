package ru.warland.help;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnerHelpTest {
    private static final class Fixture {
        final CommandDispatcher<String> d = new CommandDispatcher<>();
        final AtomicBoolean authenticated = new AtomicBoolean(true);
        final AtomicInteger mutations = new AtomicInteger();
        final AtomicInteger oldGui = new AtomicInteger();
        final List<String> replies = new ArrayList<>();
        final Predicate<String> owner = s -> s.equals("owner") && authenticated.get();
        Fixture() {
            d.register(LiteralArgumentBuilder.<String>literal("wladmin").requires(owner)
                .executes(c -> oldGui.incrementAndGet())
                .then(LiteralArgumentBuilder.<String>literal("diag").executes(c -> mutations.incrementAndGet())));
        }
        void install() { OwnerHelpCommands.install(d, owner, (s,line) -> replies.add(line)); }
        void root(String name) { d.register(LiteralArgumentBuilder.<String>literal(name)); }
    }
    @Test void helpReplacesOnlyRootGuiAndPreservesExistingChildAndGuard() throws Exception {
        Fixture f = new Fixture();
        var root = f.d.getRoot().getChild("wladmin");
        var requirement = root.getRequirement();
        var diag = root.getChild("diag");
        f.install();
        assertSame(root, f.d.getRoot().getChild("wladmin"));
        assertSame(requirement, root.getRequirement());
        assertSame(diag, root.getChild("diag"));
        assertEquals(1, f.d.execute("wladmin", "owner"));
        assertEquals(0, f.oldGui.get());
        assertEquals(0, f.mutations.get());
        assertTrue(f.replies.stream().anyMatch(s -> s.contains("Команды владельца")));
        assertEquals(1, f.d.execute("wladmin diag", "owner"));
        assertEquals(1, f.mutations.get());
    }
    @Test void nonOwnerCannotReadRootHelpOrChild() {
        Fixture f = new Fixture(); f.install();
        for (String command : List.of("wladmin", "wladmin help", "wladmin help 1", "wladmin diag")) {
            assertThrows(Exception.class, () -> f.d.execute(command, "visitor"));
        }
        assertTrue(f.replies.isEmpty()); assertEquals(0, f.mutations.get());
    }
    @Test void cachedParseCannotBypassExpiredAuthentication() throws Exception {
        Fixture f = new Fixture(); f.install();
        var cachedRoot = f.d.parse("wladmin", "owner");
        var cachedHelp = f.d.parse("wladmin help 1", "owner");
        f.authenticated.set(false);
        assertEquals(0, f.d.execute(cachedRoot)); assertEquals(0, f.d.execute(cachedHelp));
        assertTrue(f.replies.isEmpty()); assertEquals(0, f.oldGui.get());
    }
    @Test void absentOrProtectedNodesAreHiddenAtEveryLevel() {
        Fixture f = new Fixture(); f.root("season");
        f.d.register(LiteralArgumentBuilder.<String>literal("season")
            .then(LiteralArgumentBuilder.<String>literal("schedule").requires(s -> false)));
        assertFalse(OwnerHelpCommands.available(f.d,"owner",List.of("citybuild","purchases")));
        assertFalse(OwnerHelpCommands.available(f.d,"owner",List.of("season","schedule")));
        assertFalse(OwnerHelpCommands.available(f.d,"visitor",List.of("wladmin","diag")));
        assertTrue(OwnerHelpCommands.available(f.d,"owner",List.of("wladmin","diag")));
        assertEquals(List.of("wladmin diag", "season"), OwnerHelpCatalog.visible(p ->
            OwnerHelpCommands.available(f.d,"owner",p)).stream().map(OwnerHelpCatalog.Entry::path).toList());
    }
    @Test void invalidPagesAreBoundedAndNoFallbackMutationRuns() throws Exception {
        Fixture f = new Fixture(); f.install();
        assertEquals(0, f.d.execute("wladmin help 2147483647", "owner"));
        assertEquals(1, f.replies.size()); assertTrue(f.replies.getFirst().contains("Страница справки"));
        assertThrows(Exception.class, () -> f.d.execute("wladmin help 0", "owner"));
        assertThrows(Exception.class, () -> f.d.execute("wladmin help -1", "owner"));
        assertThrows(Exception.class, () -> f.d.execute("wladmin help 1 trailing", "owner"));
        assertEquals(0, f.mutations.get());
    }
    @Test void installationFailsOnMissingOrConflictingRootWithoutReplacingIt() {
        assertThrows(IllegalStateException.class, () -> OwnerHelpCommands.install(new CommandDispatcher<String>(),s->true,(s,t)->{}));
        Fixture f = new Fixture(); f.install();
        var help = f.d.getRoot().getChild("wladmin").getChild("help");
        assertThrows(IllegalStateException.class, f::install);
        assertSame(help, f.d.getRoot().getChild("wladmin").getChild("help"));
    }
    @Test void allPagesCoverExactlyTheFilteredCatalogAndAreImmutable() {
        var visible = OwnerHelpCatalog.visible(p -> true);
        var all = new ArrayList<OwnerHelpCatalog.Entry>();
        for (int p = 1; p <= OwnerHelpCatalog.pages(visible); p++) {
            var page = OwnerHelpCatalog.page(visible,p);
            assertTrue(page.size() <= OwnerHelpCatalog.PAGE_SIZE);
            assertThrows(UnsupportedOperationException.class, page::clear);
            all.addAll(page);
        }
        assertEquals(visible,all);
        assertEquals(27,all.size());
        assertEquals(all.size(),all.stream().map(OwnerHelpCatalog.Entry::path).distinct().count());
    }
    @Test void emptyCatalogAndOverflowPageStaySafe() {
        assertEquals(1,OwnerHelpCatalog.pages(List.of()));
        assertEquals(List.of(),OwnerHelpCatalog.page(List.of(),1));
        assertThrows(IllegalArgumentException.class,()->OwnerHelpCatalog.page(List.of(),0));
        assertThrows(IllegalArgumentException.class,()->OwnerHelpCatalog.page(OwnerHelpCatalog.ENTRIES,Integer.MAX_VALUE));
    }
    @Test void catalogContainsAllCurrentWarLandPrivilegedPathsButNoBootstrapOrVanillaOp() {
        Set<String> paths = new HashSet<>(OwnerHelpCatalog.ENTRIES.stream().map(OwnerHelpCatalog.Entry::path).toList());
        assertTrue(paths.containsAll(Set.of("wladmin diag","wladmin reports","wladmin resolve",
            "staff role","staff revoke","staff ban","staff mute","staff kick","staff unban","staff unmute",
            "staff diag","staff reports","staff resolve","capital resume","capital budget",
            "citybuild purchases","citybuild recover","arsenal issue","gunfocus","vehicle grant",
            "vehicle recover","season schedule","season cancel")));
        assertTrue(paths.stream().noneMatch(p -> p.contains("bootstrap") || p.equals("op") || p.startsWith("market")));
    }
    @Test void examplesArePlainDocumentedTextAndDangerousGatesAreNotAdvertisedAsReady() {
        for (var e : OwnerHelpCatalog.ENTRIES) {
            assertTrue(e.usage().startsWith("/" + e.path()));
            assertTrue(e.example().startsWith("/" + e.path()));
            assertFalse(e.description().isBlank());
            assertTrue(e.description().length() < 500);
            assertFalse((e.usage()+e.description()+e.example()).contains("\n"));
        }
        var purchases = OwnerHelpCatalog.ENTRIES.stream().filter(e->e.path().equals("citybuild purchases")).findFirst().orElseThrow();
        assertEquals("/citybuild purchases false",purchases.example());
        assertTrue(purchases.description().contains("неатомарен"));
        assertTrue(purchases.description().contains("ЭКСПЕРИМЕНТ"));
    }
    @Test void displayedExamplesNeverInvokeExistingMutationHandlers() throws Exception {
        Fixture f = new Fixture();
        for (var e : OwnerHelpCatalog.ENTRIES) {
            var nodes=e.nodes();
            var root=LiteralArgumentBuilder.<String>literal(nodes.getFirst());
            if(nodes.size()>1) root.then(LiteralArgumentBuilder.<String>literal(nodes.get(1)).executes(c->f.mutations.incrementAndGet()));
            if(!nodes.getFirst().equals("wladmin")) f.d.register(root);
        }
        f.install();
        var visible=OwnerHelpCatalog.visible(p->OwnerHelpCommands.available(f.d,"owner",p));
        for(int p=1;p<=OwnerHelpCatalog.pages(visible);p++) {
            f.replies.clear();assertEquals(1,f.d.execute("wladmin help "+p,"owner"));
            assertTrue(f.replies.size()<=11);
            assertTrue(f.replies.stream().anyMatch(s->s.contains("Пример:")));
        }
        assertEquals(0,f.mutations.get());assertEquals(0,f.oldGui.get());
    }
    @Test void dedicatedInitializerRunsHelpOnceAfterCoreAndClientGuard() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/warland/WarLand.java"));
        int call=source.indexOf("OwnerHelp.register(runtime);");
        assertTrue(call>source.indexOf("runtime.initialize();"));
        assertTrue(source.indexOf("runtime.initialize();")>source.indexOf("EnvType.CLIENT)return;"));
        assertEquals(call,source.lastIndexOf("OwnerHelp.register(runtime);"));
        String adapter=Files.readString(Path.of("src/main/java/ru/warland/help/OwnerHelp.java"));
        assertTrue(adapter.contains("runtime.staff(source, \"owner\")"));
        assertTrue(adapter.contains("source.sendFeedback(() -> Text.literal(line), false)"));
        for(String file:List.of("OwnerHelp.java","OwnerHelpCommands.java","OwnerHelpCatalog.java")) {
            String text=Files.readString(Path.of("src/main/java/ru/warland/help",file));
            for(String forbidden:List.of("parseAndExecute(",".getState(",".setState(",".menu(","RUN_COMMAND","Store.update"))
                assertFalse(text.contains(forbidden),file+" must remain read-only: "+forbidden);
        }
    }
}
