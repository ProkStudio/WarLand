package ru.warland.auth;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthDialogTest {
    private static final UUID NONCE = UUID.fromString("ddba3989-e40e-4cde-8e22-05e67a5fd312");
    private static final String PASSWORD = "Synthetic-pass-2026";
    private static NbtCompound fields() {
        var n = new NbtCompound();
        n.putString("nonce", NONCE.toString()); n.putString("password", PASSWORD);
        n.putString("confirmation", PASSWORD); n.putString("owner", ""); return n;
    }
    private static CustomClickActionC2SPacket packet(Identifier id, NbtElement n) {
        return new CustomClickActionC2SPacket(id, Optional.ofNullable(n));
    }
    private static CustomClickActionC2SPacket registration(NbtCompound n) { return packet(NativeAuthDialog.REGISTER, n); }
    @Test void registrationPreservesNonceAndOwnedBuffers() {
        try (var r = NativeAuthDialog.decode(registration(fields()), NONCE)) {
            assertTrue(r.registration); assertTrue(r.valid()); assertEquals(NONCE, r.nonce);
            assertArrayEquals(PASSWORD.toCharArray(), r.password()); assertNull(r.ownerToken());
        }
    }
    @Test void loginCannotEnrollOwnerThroughRegistrationFields() {
        var n = fields(); n.putString("owner", "A".repeat(43));
        try (var r = NativeAuthDialog.decode(packet(NativeAuthDialog.LOGIN, n), NONCE)) {
            assertFalse(r.registration); assertTrue(r.valid()); assertNull(r.ownerToken());
        }
    }
    @Test void closingRequestErasesOwnedPassword() {
        var r = NativeAuthDialog.decode(registration(fields()), NONCE); char[] owned = r.password();
        r.close(); assertArrayEquals(new char[PASSWORD.length()], owned);
    }
    @Test void missingPayloadIsRejected() { assertFalse(NativeAuthDialog.bounded(packet(NativeAuthDialog.LOGIN, null))); }
    @Test void nullPacketIsRejected() { assertFalse(NativeAuthDialog.bounded(null)); }
    @Test void nonCompoundPayloadIsRejected() { assertFalse(NativeAuthDialog.bounded(packet(NativeAuthDialog.LOGIN, NbtString.of(PASSWORD)))); }
    @Test void unknownActionIsRejected() { assertFalse(NativeAuthDialog.bounded(packet(Identifier.of("warland", "run_command"), fields()))); }
    @Test void wrongNamespaceIsRejected() { assertFalse(NativeAuthDialog.bounded(packet(Identifier.of("other", "auth/login"), fields()))); }
    @Test void additionalFieldsAreRejected() { var n=fields(); n.putString("command", "op anybody"); assertFalse(NativeAuthDialog.bounded(registration(n))); }
    @Test void eachMissingFieldIsRejected() {
        for (String key : new String[]{"nonce","password","confirmation","owner"}) {
            var n=fields(); n.remove(key); assertFalse(NativeAuthDialog.bounded(registration(n)), key);
        }
    }
    @Test void wrongFieldTypesAreRejected() {
        for (String key : new String[]{"nonce","password","confirmation","owner"}) {
            var n=fields(); n.putInt(key,1); assertFalse(NativeAuthDialog.bounded(registration(n)),key);
        }
    }
    @Test void oversizedFieldsAreRejected() {
        for (String key : new String[]{"nonce","password","confirmation","owner"}) {
            var n=fields(); n.putString(key,"A".repeat(key.equals("nonce")?37:key.equals("owner")?44:129));
            assertFalse(NativeAuthDialog.bounded(registration(n)),key);
        }
    }
    @Test void maximumPasswordAndProofAreAcceptedByEnvelope() {
        var n=fields(); n.putString("password","p".repeat(128)); n.putString("confirmation","p".repeat(128)); n.putString("owner","A".repeat(43));
        try(var r=NativeAuthDialog.decode(registration(n),NONCE)){ assertTrue(r.valid()); assertEquals(43,r.ownerToken().length()); }
    }
    @Test void tooShortPasswordRemainsInvalid() {
        var n=fields(); n.putString("password","short"); n.putString("confirmation","short");
        try(var r=NativeAuthDialog.decode(registration(n),NONCE)){assertFalse(r.valid());}
    }
    @Test void mismatchedConfirmationRemainsInvalid() {
        var n=fields(); n.putString("confirmation","another-password");
        try(var r=NativeAuthDialog.decode(registration(n),NONCE)){assertFalse(r.valid());}
    }
    @Test void controlCharactersAreRejectedInEveryField() {
        for(String key:new String[]{"password","confirmation","owner"})for(char c:new char[]{0,'\n','\r','\t',127,159}){
            var n=fields(); n.putString(key,"Example-pass"+c); assertFalse(NativeAuthDialog.bounded(registration(n)));
        }
    }
    @Test void isolatedSurrogatesAreRejected() {
        for(char c:new char[]{Character.MIN_HIGH_SURROGATE,Character.MIN_LOW_SURROGATE}){
            var n=fields(); n.putString("password","Example-pass"+c); assertFalse(NativeAuthDialog.bounded(registration(n)));
        }
    }
    @Test void validUnicodeIsAccepted() {
        var n=fields(); String p="Отдельный-пароль-"+new String(Character.toChars(0x1f30d));
        n.putString("password",p);n.putString("confirmation",p);
        try(var r=NativeAuthDialog.decode(registration(n),NONCE)){assertTrue(r.valid());}
    }
    @Test void staleOrMissingExpectedNonceIsRejected() {
        assertFalse(NativeAuthDialog.nonceMatches(registration(fields()),UUID.randomUUID()));
        assertFalse(NativeAuthDialog.nonceMatches(registration(fields()),null));
    }
    @Test void alteredNonceTextIsRejected() {
        var n=fields(); n.putString("nonce",NONCE.toString().toUpperCase());
        assertFalse(NativeAuthDialog.nonceMatches(registration(n),NONCE));
    }
    @Test void cancelIsBoundButCannotDecodeToCredentials() {
        var p=packet(NativeAuthDialog.CANCEL,fields()); assertTrue(NativeAuthDialog.nonceMatches(p,NONCE));
        assertThrows(IllegalArgumentException.class,()->NativeAuthDialog.decode(p,NONCE));
    }
    @Test void rejectedInputAndRequestStringsDoNotEchoSecrets() {
        var n=fields(); n.putString("nonce",PASSWORD);
        var error=assertThrows(IllegalArgumentException.class,()->NativeAuthDialog.decode(registration(n),NONCE));
        assertFalse(error.toString().contains(PASSWORD));
        try(var r=NativeAuthDialog.decode(registration(fields()),NONCE)){assertFalse(r.toString().contains(PASSWORD));}
    }
}
