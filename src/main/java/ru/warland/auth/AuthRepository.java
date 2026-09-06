package ru.warland.auth;

import ru.warland.data.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Credential persistence only: does not authorize a Minecraft connection or grant OP. */
public final class AuthRepository {
    public static final String REQUESTED_OWNER = "egorkrid666";
    public record Account(UUID player, String name, Passwords.Hash password, long revision) {}
    private final Store store;
    private final String reservedOwner;
    private final LongSupplier clock;
    public AuthRepository(Store store) { this(store, REQUESTED_OWNER); }
    public AuthRepository(Store store, String reservedOwner) {
        this(store, reservedOwner, System::currentTimeMillis);
    }
    /** Package-private clock seam for deterministic expiry/queue tests, never client input. */
    AuthRepository(Store store, String reservedOwner, LongSupplier clock) {
        this.store = Objects.requireNonNull(store); this.reservedOwner = name(reservedOwner);
        this.clock = Objects.requireNonNull(clock);
    }
    public CompletableFuture<Void> start() {
        return store.tx(c -> {
            Store.update(c, "CREATE TABLE IF NOT EXISTS auth_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            Store.update(c, "INSERT OR IGNORE INTO auth_schema(id,version) VALUES(1,1)");
            require(Store.scalar(c, "SELECT version FROM auth_schema WHERE id=1") == 1, "Unsupported authentication schema");
            Store.update(c, "CREATE TABLE IF NOT EXISTS auth_accounts(uuid TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE,password_hash TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),created INTEGER NOT NULL)");
            Store.update(c, "CREATE TABLE IF NOT EXISTS auth_owner(id INTEGER PRIMARY KEY CHECK(id=1),reserved_name TEXT NOT NULL,uuid TEXT REFERENCES auth_accounts(uuid),challenge_hash TEXT,expires INTEGER NOT NULL DEFAULT 0)");
            Store.update(c, "INSERT OR IGNORE INTO auth_owner(id,reserved_name) VALUES(1,?)", reservedOwner);
            require(reservedOwner.equals(Store.string(c, "SELECT reserved_name FROM auth_owner WHERE id=1")), "Owner reservation differs from durable state");
            return null;
        });
    }

    /** Console/private provisioning only. Input is a SHA-256 digest of a random 256-bit token. */
    public CompletableFuture<Void> installOwnerChallenge(String digest, long expires, long now) {
        return store.tx(c -> {
            long installedAt = executionTime(now);
            require(validDigest(digest) && expires > now && expires - now <= 86_400_000L
                    && expires > installedAt, "Invalid or expired owner challenge");
            require(Store.string(c, "SELECT uuid FROM auth_owner WHERE id=1") == null, "Owner is already bound");
            require(Store.update(c, "UPDATE auth_owner SET challenge_hash=?,expires=? WHERE id=1 AND uuid IS NULL", digest, expires) == 1, "Owner reservation missing");
            audit(c, "console", "auth.owner-challenge", reservedOwner, installedAt);
            // A delay inside SQL must roll back the replacement and its audit together.
            require(expires > executionTime(installedAt), "Owner challenge expired before installation");
            return null;
        });
    }

    /** Hash must be derived off the tick and database threads from server-owned input. */
    public CompletableFuture<Account> register(UUID player, String nickname, Passwords.Hash password,
                                                String ownerChallengeDigest, long now) {
        return store.tx(c -> {
            Objects.requireNonNull(player); Objects.requireNonNull(password);
            String normalized = name(nickname); long registeredAt = executionTime(now);
            boolean reserved = normalized.equals(reservedOwner);
            if (reserved) {
                String expected = Store.string(c, "SELECT challenge_hash FROM auth_owner WHERE id=1 AND uuid IS NULL AND expires>?", registeredAt);
                require(validDigest(ownerChallengeDigest) && expected != null && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII), ownerChallengeDigest.getBytes(StandardCharsets.US_ASCII)), "Owner account requires private bootstrap proof");
            } else require(ownerChallengeDigest == null, "Unexpected owner challenge");
            Store.update(c, "INSERT INTO auth_accounts(uuid,name,password_hash,revision,created) VALUES(?,?,?,1,?)",
                    player.toString(), normalized, password.encoded(), registeredAt);
            Account account = find(c, player, normalized);
            audit(c, player.toString(), reserved ? "auth.owner-bound" : "auth.register", normalized, registeredAt);
            // Last SQL write before commit: expiry/hash CAS also rolls back account + audit on denial.
            if (reserved) require(Store.update(c, "UPDATE auth_owner SET uuid=?,challenge_hash=NULL,expires=0 WHERE id=1 AND uuid IS NULL AND challenge_hash=? AND expires>?",
                    player.toString(), ownerChallengeDigest, executionTime(registeredAt)) == 1, "Owner bootstrap expired or changed before binding");
            return account;
        });
    }
    public CompletableFuture<Account> account(UUID player, String nickname) {
        return store.submit(c -> find(c, player, name(nickname)));
    }
    public CompletableFuture<UUID> owner() {
        return store.submit(c -> { String id = Store.string(c, "SELECT uuid FROM auth_owner WHERE id=1"); return id == null ? null : UUID.fromString(id); });
    }
    /** Recheck after expensive verification to reject a password changed during the login. */
    public CompletableFuture<Boolean> current(UUID player, long revision) {
        return store.submit(c -> revision > 0 && Store.scalar(c, "SELECT COUNT(*) FROM auth_accounts WHERE uuid=? AND revision=?", player.toString(), revision) == 1);
    }
    public CompletableFuture<Boolean> changePassword(UUID player, long expectedRevision, Passwords.Hash replacement, long now) {
        return store.tx(c -> {
            Objects.requireNonNull(replacement); require(now > 0 && expectedRevision > 0 && expectedRevision < Long.MAX_VALUE, "Invalid credential revision");
            int changed = Store.update(c, "UPDATE auth_accounts SET password_hash=?,revision=revision+1 WHERE uuid=? AND revision=?",
                    replacement.encoded(), player.toString(), expectedRevision);
            if (changed == 1) audit(c, player.toString(), "auth.password-changed", "credentials", now);
            return changed == 1;
        });
    }
    /** Request time is only a lower bound, not authority to extend a persisted deadline. */
    private long executionTime(long requestedAt) throws SQLException {
        long current = clock.getAsLong();
        require(requestedAt > 0 && current > 0, "Invalid timestamp");
        return Math.max(requestedAt, current);
    }
    private static Account find(Connection c, UUID player, String name) throws SQLException {
        try (var p = c.prepareStatement("SELECT password_hash,revision FROM auth_accounts WHERE uuid=? AND name=?")) {
            Store.bind(p, player.toString(), name);
            try (var result = p.executeQuery()) {
                return result.next() ? new Account(player, name, new Passwords.Hash(result.getString(1)), result.getLong(2)) : null;
            }
        }
    }
    public static String name(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{3,16}")) throw new IllegalArgumentException("Invalid Minecraft account name");
        return value.toLowerCase(Locale.ROOT);
    }
    private static boolean validDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void require(boolean value, String message) throws SQLException { if (!value) throw new SQLException(message); }
    private static void audit(Connection c, String actor, String action, String target, long now) throws SQLException {
        Store.update(c, "INSERT INTO audit(actor,action,target,created) VALUES(?,?,?,?)", actor, action, target, now);
    }
}
