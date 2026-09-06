package ru.warland.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Passwords never become Strings. Callers own and must clear their input char arrays. */
public final class Passwords {
    public static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private Passwords() {}

    public record Hash(String encoded) {
        public Hash { parse(encoded); }
        @Override public String toString() { return "PasswordHash[redacted]"; }
    }
    private record Parts(byte[] salt, byte[] digest) {}

    public static Hash hash(char[] password) {
        validate(password);
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        byte[] digest = derive(password, salt);
        try {
            return new Hash("wl-pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(digest));
        } finally { Arrays.fill(digest, (byte) 0); }
    }
    public static boolean verify(char[] password, Hash hash) {
        if (password == null || password.length < 12 || password.length > 128 || hash == null) return false;
        Parts parts = parse(hash.encoded());
        byte[] actual = derive(password, parts.salt());
        try { return MessageDigest.isEqual(parts.digest(), actual); }
        finally { Arrays.fill(actual, (byte) 0); Arrays.fill(parts.digest(), (byte) 0); }
    }
    public static void validate(char[] password) {
        if (password == null || password.length < 12 || password.length > 128)
            throw new IllegalArgumentException("Password must contain 12..128 characters");
    }
    private static Parts parse(String encoded) {
        if (encoded == null || encoded.length() > 160) throw new IllegalArgumentException("Invalid password hash");
        String[] fields = encoded.split("\\$", -1);
        if (fields.length != 4 || !fields[0].equals("wl-pbkdf2-sha256") || !fields[1].equals("600000"))
            throw new IllegalArgumentException("Unsupported password hash");
        try {
            byte[] salt = Base64.getDecoder().decode(fields[2]), digest = Base64.getDecoder().decode(fields[3]);
            if (salt.length != 16 || digest.length != 32
                    || !Base64.getEncoder().encodeToString(salt).equals(fields[2])
                    || !Base64.getEncoder().encodeToString(digest).equals(fields[3]))
                throw new IllegalArgumentException("Invalid password hash shape");
            return new Parts(salt, digest);
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid password hash"); }
    }
    private static byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Required password KDF unavailable", e); }
        finally { spec.clearPassword(); }
    }
}
