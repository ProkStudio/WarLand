package ru.warland.economy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** JSON envelope tests only. Actual ItemStack registry roundtrip needs Fabric runtime. */
class MarketStackCodecTest {
    @Test void canonicalizesNestedObjectKeysWithoutSortingArrays() {
        var json = JsonParser.parseString("{\"z\":[3,1],\"a\":{\"z\":2,\"a\":1}}");
        assertEquals("{\"a\":{\"a\":1,\"z\":2},\"z\":[3,1]}", MarketStackCodec.canonicalJson(json));
    }
    @Test void rejectsOversizedInputBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () -> MarketStackCodec.validateJsonBounds("x".repeat(65537)));
    }
    @Test void rejectsDeepInputBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () -> MarketStackCodec.validateJsonBounds("[".repeat(33) + "0" + "]".repeat(33)));
    }
    @Test void quotedBracesAndEscapesDoNotCountAsNesting() {
        assertDoesNotThrow(() -> MarketStackCodec.validateJsonBounds("{\"v\":\"[[[\\\"{}\"}"));
    }
    @Test void rejectsUnbalancedAndUnterminatedInput() {
        for (String input : new String[]{"", " ", "{", "}", "{\"v\":\"x}"})
            assertThrows(IllegalArgumentException.class, () -> MarketStackCodec.validateJsonBounds(input));
    }
    @Test void limitsJsonNodeBudget() {
        var json = JsonParser.parseString("[" + "0,".repeat(8192) + "0]");
        assertThrows(IllegalArgumentException.class, () -> MarketStackCodec.canonicalJson(json));
    }
}
