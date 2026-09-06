package ru.warland.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Thread-safe connection generations and bounded pre-KDF throttling; no Minecraft hooks yet. */
public final class Admission {
    private static final long LOGIN_TTL = TimeUnit.SECONDS.toNanos(120);
    private static final long SESSION_TTL = TimeUnit.HOURS.toNanos(8);
    private record Session(UUID nonce, long since, boolean authenticated) {}
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final LongSupplier clock;
    public Admission() { this(System::nanoTime); }
    public Admission(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    /** Explicit generation replacement for trusted callers; never use for network admission. */
    public synchronized UUID open(UUID player) { return open(player, false); }

    /** Reserve a UUID without invalidating an incumbent pending or authenticated connection. */
    public synchronized UUID reserve(UUID player) { return open(player, true); }

    private UUID open(UUID player, boolean exclusive) {
        Objects.requireNonNull(player); long now = clock.getAsLong();
        sessions.values().removeIf(s -> !valid(s, now));
        if (exclusive && sessions.containsKey(player)) throw new IllegalStateException("Authentication unavailable");
        if (!sessions.containsKey(player) && sessions.size() >= 128) throw new IllegalStateException("Authentication capacity reached");
        UUID nonce = UUID.randomUUID(); sessions.put(player, new Session(nonce, now, false)); return nonce;
    }
    public synchronized boolean pending(UUID player, UUID nonce) {
        Session s = sessions.get(player);
        return s != null && s.nonce().equals(nonce) && !s.authenticated() && valid(s, clock.getAsLong());
    }
    public synchronized boolean authenticate(UUID player, UUID nonce) {
        Session s = sessions.get(player); long now = clock.getAsLong();
        if (s == null || !s.nonce().equals(nonce) || !valid(s, now)) return false;
        if (!s.authenticated()) sessions.put(player, new Session(nonce, now, true));
        return true;
    }
    public synchronized boolean authenticated(UUID player, UUID nonce) {
        Session s = sessions.get(player);
        return s != null && s.nonce().equals(nonce) && s.authenticated() && valid(s, clock.getAsLong());
    }
    public synchronized void close(UUID player, UUID nonce) {
        Session s = sessions.get(player); if (s != null && s.nonce().equals(nonce)) sessions.remove(player);
    }
    private static boolean valid(Session s, long now) {
        long elapsed = now - s.since();
        return elapsed >= 0 && elapsed < (s.authenticated() ? SESSION_TTL : LOGIN_TTL);
    }

    /** Account, peer and global fixed-window caps. Keys come from trusted connection metadata. */
    public static final class Throttle {
        private static final long WINDOW = TimeUnit.MINUTES.toNanos(1);
        private record Bucket(long since, int attempts) {}
        private final Map<String, Bucket> buckets = new HashMap<>();
        private final LongSupplier clock;
        public Throttle() { this(System::nanoTime); }
        public Throttle(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
        public synchronized boolean acquire(String nickname, String peer) {
            String name = AuthRepository.name(nickname);
            if (peer == null || peer.isBlank() || peer.length() > 128) return false;
            long now = clock.getAsLong();
            buckets.values().removeIf(b -> elapsed(b, now) >= WINDOW);
            String[] keys = {"all", "account:" + name, "peer:" + peer};
            int[] limits = {40, 5, 20};
            int missing = 0;
            for (int i = 0; i < keys.length; i++) {
                Bucket b = buckets.get(keys[i]);
                if (b == null) missing++;
                else if (elapsed(b, now) < 0 || b.attempts() >= limits[i]) return false;
            }
            if (buckets.size() + missing > 256) return false; // Never evict live limits to accept attacker keys.
            for (String key : keys) {
                Bucket b = buckets.get(key);
                buckets.put(key, b == null ? new Bucket(now, 1) : new Bucket(b.since(), b.attempts() + 1));
            }
            return true;
        }
        private static long elapsed(Bucket b, long now) { return now - b.since(); }
    }
}
