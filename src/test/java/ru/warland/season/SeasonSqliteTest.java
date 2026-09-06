package ru.warland.season;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.warland.data.Store;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.season.SeasonRulesTest.*;

/** Uses a temporary real SQLite database; never a running server or production data. */
class SeasonSqliteTest {
    @TempDir Path directory;
    private final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
    private void completion() throws Exception {
        Runnable task = callbacks.poll(10, TimeUnit.SECONDS);
        assertNotNull(task, "Timed out waiting for a storage completion");
        task.run();
    }
    private SeasonStore load(Store db) throws Exception {
        var season = new SeasonStore(() -> db.state(SeasonStore.NAMESPACE, SeasonStore.KEY),
                json -> db.state(SeasonStore.NAMESPACE, SeasonStore.KEY, json), callbacks::add,
                error -> fail("Unexpected persistence failure", error));
        season.load(() -> {}); completion(); assertTrue(season.ready()); return season;
    }
    @Test void lifecycleSurvivesDatabaseReopenAndLeavesOtherNamespacesUntouched() throws Exception {
        Path path = directory.resolve("season-test.db");
        Store first = new Store(path);
        try {
            first.start().get(10, TimeUnit.SECONDS);
            first.state("content", "sentinel", "unchanged").get(10, TimeUnit.SECONDS);
            SeasonStore season = load(first);
            season.change(old -> scheduled(), change -> assertTrue(change.changed()), error -> fail(error));
            assertEquals(0, season.snapshot().revision()); completion();
            assertEquals(scheduled(), season.snapshot()); season.close();
        } finally { first.close(); }
        Store reopened = new Store(path);
        try {
            reopened.start().get(10, TimeUnit.SECONDS);
            SeasonStore season = load(reopened);
            assertEquals(scheduled(), season.snapshot());
            season.change(old -> SeasonRules.advance(old, at(SeasonState.endFor(START) + SeasonState.DAY)),
                    change -> assertEquals(SeasonState.Phase.ARCHIVED, change.after().seasons().getFirst().phase()), error -> fail(error));
            completion();
            String durable = reopened.state(SeasonStore.NAMESPACE, SeasonStore.KEY).get(10, TimeUnit.SECONDS);
            assertEquals(season.snapshot(), SeasonStore.decode(durable));
            assertEquals(5, SeasonStore.decode(durable).seasons().getFirst().history().size());
            assertEquals("unchanged", reopened.state("content", "sentinel").get(10, TimeUnit.SECONDS));
            season.close();
        } finally { reopened.close(); }
    }
}
