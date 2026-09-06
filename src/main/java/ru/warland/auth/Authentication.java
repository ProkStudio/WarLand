package ru.warland.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Async engine, not Minecraft hooks. Password methods consume the supplied char array. */
public final class Authentication implements AutoCloseable {
    public enum Result { SUCCESS, DENIED }
    public record Connection(UUID player, UUID nonce, String name, String peer) {}
    private final AuthRepository repository;
    private final Admission admission;
    private final Admission.Throttle throttle;
    private final Semaphore capacity = new Semaphore(9);
    private final Set<Request> requests = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor crypto = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> { Thread t = new Thread(r, "warland-auth-kdf"); t.setDaemon(true); return t; });
    private volatile boolean closed;
    private static final Passwords.Hash DUMMY = new Passwords.Hash("wl-pbkdf2-sha256$600000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    public Authentication(AuthRepository repository) { this(repository, new Admission(), new Admission.Throttle()); }
    public Authentication(AuthRepository repository, Admission admission, Admission.Throttle throttle) {
        this.repository = Objects.requireNonNull(repository); this.admission = Objects.requireNonNull(admission); this.throttle = Objects.requireNonNull(throttle);
    }
    public Connection open(UUID player, String name, String peer) {
        if (closed || peer == null || peer.isBlank() || peer.length() > 128) throw new IllegalStateException("Authentication unavailable");
        String canonical = AuthRepository.name(name);
        Connection current = new Connection(player, admission.open(player), canonical, peer);
        for (Request r : requests) if (r.connection.player().equals(player) && !r.connection.nonce().equals(current.nonce())) r.cancel();
        return current;
    }
    public boolean authenticated(Connection c) { return !closed && admission.authenticated(c.player(), c.nonce()); }
    public void disconnect(Connection c) {
        admission.close(c.player(), c.nonce());
        for (Request r : requests) if (r.connection.equals(c)) r.cancel();
    }
    public CompletableFuture<Result> register(Connection c, char[] password, String ownerToken, long now) {
        return consume(c, password, request -> {
            if (!allowed(c)) return denied();
            String digest = ownerToken == null ? null : tokenDigest(ownerToken);
            return work(request, Passwords::hash).thenCompose(hash -> {
                if (!request.live() || closed || !admission.pending(c.player(), c.nonce())) return denied();
                return repository.register(c.player(), c.name(), hash, digest, now).thenApply(account -> request.publish());
            });
        });
    }
    public CompletableFuture<Result> login(Connection c, char[] password) {
        return consume(c, password, request -> {
            if (!allowed(c)) return denied();
            return repository.account(c.player(), c.name()).thenCompose(account ->
                    work(request, secret -> Passwords.verify(secret, account == null ? DUMMY : account.password()))
                            .thenCompose(ok -> {
                                if (!ok || account == null || closed || !request.live()) return denied();
                                return repository.current(c.player(), account.revision()).thenApply(current -> current ? request.publish() : Result.DENIED);
                            }));
        });
    }
    public CompletableFuture<Boolean> owner(Connection c) {
        if (!authenticated(c)) return CompletableFuture.completedFuture(false);
        return repository.owner().thenApply(owner -> c.player().equals(owner) && authenticated(c));
    }
    /** Real console/private provisioning only; never expose to player or command-block sources. */
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
    private static CompletableFuture<Result> denied() { return CompletableFuture.completedFuture(Result.DENIED); }
    private CompletableFuture<Result> consume(Connection connection, char[] input, Function<Request, CompletableFuture<Result>> action) {
        if (input == null) return denied();
        if (input.length < 12 || input.length > 128 || closed || !capacity.tryAcquire()) {
            Arrays.fill(input, '\0'); return denied();
        }
        Request request = new Request(connection, input.clone()); Arrays.fill(input, '\0'); requests.add(request);
        try {
            if (closed) { request.cancel(); request.finish(Result.DENIED); }
            else action.apply(request).whenComplete((value, error) -> request.finish(error == null && value == Result.SUCCESS ? Result.SUCCESS : Result.DENIED));
        } catch (RuntimeException invalid) { request.finish(Result.DENIED); }
        return request.exposed;
    }
    private final class Request {
        private final Connection connection;
        private final char[] secret;
        private final CompletableFuture<Result> exposed = new CompletableFuture<>();
        private boolean cancelled, finished, usingSecret;
        private Request(Connection connection, char[] secret) {
            this.connection = connection; this.secret = secret;
            // This private stage is never exposed, so caller cancellation cannot skip cleanup.
            exposed.whenComplete((v, e) -> { if (exposed.isCancelled()) cancel(); });
        }
        private synchronized boolean live() { return !cancelled && !finished; }
        private synchronized Result publish() {
            return live() && !closed && admission.authenticate(connection.player(), connection.nonce()) ? Result.SUCCESS : Result.DENIED;
        }
        private <T> T useSecret(Function<char[], T> operation) {
            synchronized (this) {
                if (!live() || closed) throw new CancellationException("Authentication cancelled");
                usingSecret = true;
            }
            try { return operation.apply(secret); }
            finally { synchronized (this) { usingSecret = false; if (cancelled || finished) Arrays.fill(secret, '\0'); } }
        }
        private void cancel() {
            synchronized (this) {
                cancelled = true;
                if (!usingSecret) Arrays.fill(secret, '\0');
                admission.close(connection.player(), connection.nonce());
            }
            exposed.complete(Result.DENIED);
            // Keep the permit until internal DB/KDF work settles: disconnect churn cannot grow Store's queue.
        }
        private void finish(Result value) {
            synchronized (this) {
                if (finished) return;
                finished = true;
                if (!usingSecret) Arrays.fill(secret, '\0');
                if (cancelled || closed) value = Result.DENIED;
            }
            requests.remove(this); capacity.release(); exposed.complete(value);
        }
    }
    private <T> CompletableFuture<T> work(Request request, Function<char[], T> operation) {
        Task<T> task = new Task<>(() -> request.useSecret(operation));
        if (closed || !request.live()) task.reject();
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
        for (Request request : requests) request.cancel();
        for (Runnable pending : crypto.shutdownNow()) if (pending instanceof Task<?> task) task.reject();
    }
}
