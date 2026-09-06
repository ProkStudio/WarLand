package ru.warland.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bounded async authentication engine. The future Minecraft adapter must enforce secure transport,
 * pre-auth packet isolation and a connection nonce. Merely registering commands is NOT sufficient.
 * Public password-taking methods consume and clear the provided char array, including failures.
 */
public final class Authentication implements AutoCloseable {
    public enum Result { SUCCESS, DENIED }
    public record Connection(UUID player, UUID nonce, String name, String peer) {}
    private final AuthRepository repository;
    private final Admission admission;
    private final Admission.Throttle throttle;
    private final ThreadPoolExecutor crypto = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> { Thread t = new Thread(r, "warland-auth-kdf"); t.setDaemon(true); return t; });
    private volatile boolean closed;
    // Public dummy hash to give unknown users the same bounded KDF work as wrong passwords.
    private static final Passwords.Hash DUMMY = new Passwords.Hash("wl-pbkdf2-sha256$600000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    public Authentication(AuthRepository repository) { this(repository, new Admission(), new Admission.Throttle()); }
    public Authentication(AuthRepository repository, Admission admission, Admission.Throttle throttle) {
        this.repository = Objects.requireNonNull(repository); this.admission = Objects.requireNonNull(admission); this.throttle = Objects.requireNonNull(throttle);
    }
    public Connection open(UUID player, String name, String peer) {
        if (closed || peer == null || peer.isBlank() || peer.length() > 128) throw new IllegalStateException("Authentication unavailable");
        String canonical = AuthRepository.name(name);
        return new Connection(player, admission.open(player), canonical, peer);
    }
    public boolean authenticated(Connection c) { return !closed && admission.authenticated(c.player(), c.nonce()); }
    public void disconnect(Connection c) { admission.close(c.player(), c.nonce()); }
    public CompletableFuture<Result> register(Connection c, char[] password, String ownerToken, long now) {
        return consume(password, secret -> {
            if (!allowed(c)) return denied();
            String digest = ownerToken == null ? null : tokenDigest(ownerToken);
            return work(() -> Passwords.hash(secret)).thenCompose(hash -> {
                if (closed || !admission.pending(c.player(), c.nonce())) return denied();
                return repository.register(c.player(), c.name(), hash, digest, now)
                        .thenApply(account -> finish(c));
            });
        });
    }
    public CompletableFuture<Result> login(Connection c, char[] password) {
        return consume(password, secret -> {
            if (!allowed(c)) return denied();
            return repository.account(c.player(), c.name()).thenCompose(account ->
                    work(() -> Passwords.verify(secret, account == null ? DUMMY : account.password()))
                            .thenCompose(ok -> {
                                if (!ok || account == null || closed) return denied();
                                return repository.current(c.player(), account.revision()).thenApply(current -> current ? finish(c) : Result.DENIED);
                            }));
        });
    }
    public CompletableFuture<Boolean> owner(Connection c) {
        if (!authenticated(c)) return CompletableFuture.completedFuture(false);
        return repository.owner().thenApply(owner -> c.player().equals(owner) && authenticated(c));
    }
    /** This endpoint must be exposed only to the real server console, never player/command-block sources. */
    public CompletableFuture<String> provisionOwner(long now, long expires) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Authentication unavailable"));
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random); Arrays.fill(random, (byte) 0);
        return repository.installOwnerChallenge(tokenDigest(token), expires, now).thenApply(v -> token);
    }
    public static String tokenDigest(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid bootstrap proof");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Required digest unavailable", e); }
    }
    private boolean allowed(Connection c) {
        return !closed && admission.pending(c.player(), c.nonce()) && throttle.acquire(c.name(), c.peer());
    }
    private Result finish(Connection c) { return !closed && admission.authenticate(c.player(), c.nonce()) ? Result.SUCCESS : Result.DENIED; }
    private static CompletableFuture<Result> denied() { return CompletableFuture.completedFuture(Result.DENIED); }
    private CompletableFuture<Result> consume(char[] input, Function<char[], CompletableFuture<Result>> action) {
        char[] secret = input == null ? null : input.clone();
        if (input != null) Arrays.fill(input, '\0');
        try {
            Passwords.validate(secret);
            return action.apply(secret).exceptionally(error -> Result.DENIED).whenComplete((v, e) -> Arrays.fill(secret, '\0'));
        } catch (RuntimeException invalid) {
            if (secret != null) Arrays.fill(secret, '\0');
            return denied();
        }
    }
    private <T> CompletableFuture<T> work(Supplier<T> supplier) {
        Task<T> task = new Task<>(supplier);
        if (closed) task.reject();
        else try { crypto.execute(task); } catch (RejectedExecutionException full) { task.reject(); }
        return task.result;
    }
    private static final class Task<T> implements Runnable {
        private final Supplier<T> supplier;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private Task(Supplier<T> supplier) { this.supplier = supplier; }
        @Override public void run() { try { result.complete(supplier.get()); } catch (Throwable e) { result.completeExceptionally(e); } }
        private void reject() { result.completeExceptionally(new RejectedExecutionException("Authentication worker unavailable")); }
    }
    @Override public void close() {
        closed = true;
        for (Runnable pending : crypto.shutdownNow()) if (pending instanceof Task<?> task) task.reject();
    }
}
