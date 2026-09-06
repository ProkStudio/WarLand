package ru.warland.auth;

import java.util.Arrays;
import java.util.UUID;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Configuration-only protocol. No command/chat packets, no UUID/name supplied by the client. */
public final class AuthPayloads {
    private AuthPayloads() {}
    public static void register() {
        PayloadTypeRegistry.configurationC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.configurationS2C().register(Challenge.ID, Challenge.CODEC);
    }
    public record Challenge(UUID nonce, int status) implements CustomPayload {
        public static final Id<Challenge> ID = new Id<>(Identifier.of("warland", "auth_challenge"));
        public static final PacketCodec<PacketByteBuf, Challenge> CODEC = new PacketCodec<>() {
            public Challenge decode(PacketByteBuf b) { return new Challenge(b.readUuid(), b.readUnsignedByte()); }
            public void encode(PacketByteBuf b, Challenge p) { b.writeUuid(p.nonce()); b.writeByte(p.status()); }
        };
        public Id<Challenge> getId() { return ID; }
    }
    /** Owns arrays. Decoder errors and rejected/successful requests all erase owned buffers. */
    public static final class Request implements CustomPayload, AutoCloseable {
        public static final Id<Request> ID = new Id<>(Identifier.of("warland", "auth_request"));
        public static final int MAX_BYTES = 640;
        public static final PacketCodec<PacketByteBuf, Request> CODEC = new PacketCodec<>() {
            public Request decode(PacketByteBuf b) {
                char[] password = null, confirmation = null, token = null;
                try {
                    if (b.readableBytes() > MAX_BYTES) throw new DecoderException("Invalid auth frame");
                    if (b.readUnsignedByte() != 1) throw new DecoderException("Unsupported auth protocol");
                    UUID nonce = b.readUuid(); int operation = b.readUnsignedByte();
                    if (operation > 1) throw new DecoderException("Invalid auth operation");
                    password = readSecret(b, 128); confirmation = readSecret(b, 128); token = readSecret(b, 43);
                    if (b.isReadable()) throw new DecoderException("Invalid auth frame");
                    return new Request(nonce, operation == 1, password, confirmation, token);
                } catch (RuntimeException e) {
                    wipe(password); wipe(confirmation); wipe(token);
                    // Never echo malformed input or decoder causes containing a secret.
                    throw new DecoderException("Invalid auth frame");
                }
            }
            public void encode(PacketByteBuf b, Request p) {
                try {
                    b.writeByte(1); b.writeUuid(p.nonce); b.writeByte(p.registration ? 1 : 0);
                    writeSecret(b, p.password, 128); writeSecret(b, p.confirmation, 128); writeSecret(b, p.token, 43);
                } finally { p.close(); }
            }
        };
        public final UUID nonce;
        public final boolean registration;
        private final char[] password, confirmation, token;
        public Request(UUID nonce, boolean registration, char[] password, char[] confirmation, char[] token) {
            this.nonce = nonce; this.registration = registration;
            this.password = password; this.confirmation = confirmation; this.token = token;
        }
        public boolean valid() {
            return password != null && password.length >= 12 && password.length <= 128
                && confirmation != null && token != null && token.length <= 43
                && (registration ? Arrays.equals(password, confirmation) : confirmation.length == 0 && token.length == 0);
        }
        public char[] password() { return password; }
        public String ownerToken() { return token.length == 0 ? null : new String(token); }
        public void close() { wipe(password); wipe(confirmation); wipe(token); }
        @Override public String toString() { return "AuthRequest[REDACTED]"; }
    }
    private static char[] readSecret(PacketByteBuf b, int max) {
        int size = b.readUnsignedShort();
        if (size > max || b.readableBytes() < size * 2) throw new DecoderException("Invalid auth field");
        char[] chars = new char[size];
        for (int i = 0; i < size; i++) chars[i] = b.readChar();
        return chars;
    }
    private static void writeSecret(PacketByteBuf b, char[] chars, int max) {
        if (chars == null || chars.length > max) throw new IllegalArgumentException("Invalid auth field");
        b.writeShort(chars.length); for (char c : chars) b.writeChar(c);
    }
    private static void wipe(char[] chars) { if (chars != null) Arrays.fill(chars, '\0'); }
}
