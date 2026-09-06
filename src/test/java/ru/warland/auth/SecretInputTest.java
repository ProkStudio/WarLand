package ru.warland.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretInputTest {
    @Test void rejectsAsciiPlusSupplementaryOverflowBeforeClipping() {
        assertFalse(SecretInput.fits(0, 0, "a".repeat(128) + "😀", 128));
        assertFalse(SecretInput.fits(0, 0, "a".repeat(129), 128));
    }
    @Test void exactLimitAndSurrogatePairsAreAcceptedWithoutChanges() {
        assertTrue(SecretInput.fits(0, 0, "a".repeat(128), 128));
        assertTrue(SecretInput.fits(0, 0, "😀".repeat(64), 128));
        assertFalse(SecretInput.fits(0, 0, "😀".repeat(65), 128));
    }
    @Test void accountsForSelectedRangeAndExistingText() {
        assertTrue(SecretInput.fits(128, 2, "😀", 128));
        assertFalse(SecretInput.fits(128, 1, "😀", 128));
        assertTrue(SecretInput.fits(128, 128, "replacement", 128));
        assertFalse(SecretInput.fits(127, 0, "ab", 128));
        assertTrue(SecretInput.fits(127, 1, "ab", 128));
    }
    @Test void ownerTokenUsesItsOwnLimit() {
        assertTrue(SecretInput.fits(0, 0, "x".repeat(43), 43));
        assertFalse(SecretInput.fits(0, 0, "x".repeat(44), 43));
    }
    @Test void invalidSelectionNullAndUnpairedSurrogatesAreRejected() {
        assertFalse(SecretInput.fits(10, 11, "", 128));
        assertFalse(SecretInput.fits(-1, 0, "", 128));
        assertFalse(SecretInput.fits(0, 0, null, 128));
        assertFalse(SecretInput.fits(0, 0, String.valueOf((char) 0xd800), 128));
        assertFalse(SecretInput.fits(0, 0, String.valueOf((char) 0xdc00), 128));
    }
    @Test void fieldChecksWholeInsertionBeforeVanillaWriteAndStillHidesSelection() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/warland/auth/client/AuthClient.java"));
        String write = source.substring(source.indexOf("@Override public void write(String value)"));
        assertTrue(write.indexOf("SecretInput.fits(") < write.indexOf("super.write(value)"));
        assertTrue(write.contains("super.getSelectedText().length()"));
        assertTrue(write.contains("StringHelper.stripInvalidChars(value).equals(value)"));
        assertTrue(source.contains("public String getSelectedText() { return \"\"; }"));
        assertFalse(source.contains("setMaxLength(limit + 1)"));
    }
}
