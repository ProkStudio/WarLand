package ru.warland.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Single server-thread writer; no unbounded queue and no side effects before durable acknowledgement. */
final class SeasonStore {
    static final String NAMESPACE = "season", KEY = "state.v1";
    static final int MAX_JSON_CHARS = 128_000;
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    record Change(SeasonState before, SeasonState after) { boolean changed() { return !before.equals(after); } }
    private final Supplier<CompletableFuture<String>> read;
    private final Function<String, CompletableFuture<Void>> write;
    private final Executor server;
    private final Consumer<Throwable> report;
    private SeasonState state;
    private boolean busy, closed, broken, loading;

    SeasonStore(Supplier<CompletableFuture<String>> read, Function<String, CompletableFuture<Void>> write,
                Executor server, Consumer<Throwable> report) {
        this.read = read; this.write = write; this.server = server; this.report = report;
    }
    void load(Runnable loaded) {
        if (loading || state != null || closed || broken) throw new IllegalStateException("Already loaded or closed");
        loading = true;
        try {
            read.get().whenComplete((json, error) -> server.execute(() -> {
                if (closed) return;
                loading = false;
                if (error != null) { fail(error); return; }
                try {
                    state = json == null ? SeasonState.empty() : decode(json);
                } catch (RuntimeException invalid) { fail(invalid); return; }
                try { loaded.run(); } catch (RuntimeException callback) { report.accept(callback); }
            }));
        } catch (RuntimeException error) { loading = false; fail(error); }
    }
    static SeasonState decode(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_CHARS)
            throw new IllegalArgumentException("Invalid season document length");
        return Objects.requireNonNull(JSON.fromJson(json, SeasonState.class), "Null season document");
    }
    boolean ready() { return state != null && !closed && !broken && !loading; }
    boolean idle() { return ready() && !busy; }
    SeasonState snapshot() {
        if (!ready()) throw new IllegalStateException("Season storage unavailable");
        return state; // All nested values are immutable.
    }
    void change(UnaryOperator<SeasonState> edit, Consumer<Change> after, Consumer<Throwable> rejected) {
        if (!idle()) { rejected.accept(new IllegalStateException("Хранилище занято или недоступно. Повторите позже.")); return; }
        SeasonState before = state;
        SeasonState next;
        String json;
        try {
            next = Objects.requireNonNull(edit.apply(before));
            if (next.equals(before)) { after.accept(new Change(before, before)); return; }
            if (next.revision() != Math.addExact(before.revision(), 1)) throw new IllegalArgumentException("Revision conflict");
            json = JSON.toJson(next);
            if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Season document too large");
        } catch (RuntimeException invalid) { rejected.accept(invalid); return; }
        busy = true;
        try {
            write.apply(json).whenComplete((unused, error) -> server.execute(() -> {
                if (closed) return;
                busy = false;
                if (error != null) { fail(error); rejected.accept(error); return; }
                state = next;
                try { after.accept(new Change(before, next)); }
                catch (RuntimeException callback) { report.accept(callback); }
            }));
        } catch (RuntimeException error) { busy = false; fail(error); rejected.accept(error); }
    }
    private void fail(Throwable error) {
        broken = true;
        report.accept(error); // An ambiguous write requires restart/reload, never an in-memory retry.
    }
    void close() { closed = true; }
}
