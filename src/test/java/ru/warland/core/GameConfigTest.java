package ru.warland.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GameConfigTest {
    @TempDir Path temp;
    private GameConfig read(String text) throws IOException {
        Path path=temp.resolve("core.json");Files.writeString(path,text);return GameConfig.load(path);
    }
    @Test void defaultsCreateSafelyWithRiskyFeaturesDisabled() throws Exception {
        var config=GameConfig.load(temp.resolve("new/core.json"));
        config.validate();assertTrue(config.requireOnlineModeForPublic);
        assertFalse(config.enableBuyer);assertFalse(config.enableAuction);assertFalse(config.enableWarCapture);
        assertEquals(12,config.mobilizationHours);
        assertEquals(1500,GameConfig.load(temp.resolve("new/core.json")).startingBalance);
    }
    @Test void omittedFieldsUseDefaults() throws Exception {
        var config=read("{\"startingBalance\":2000}");assertEquals(2000,config.startingBalance);assertEquals(12,config.mobilizationHours);
    }
    @Test void invalidFileIsNeverReplacedWithDefaults() throws Exception {
        String invalid="{\"mobilizationHours\":0}";
        assertThrows(IOException.class,()->read(invalid));assertEquals(invalid,Files.readString(temp.resolve("core.json")));
    }
    @Test void numericCoercionAndOverflowAreRejected() {
        for(String text:new String[]{"{\"schemaVersion\":1.5}","{\"mobilizationHours\":\"12\"}",
                "{\"worldRadius\":4294973296}","{\"warCost\":9223372036854775808}",
                "{\"enableBuyer\":\"false\"}","{\"enableBuyer\":null}",
                "{\"buyerPrices\":{\"minecraft:coal\":1.9}}","{\"buyerPrices\":{\"minecraft:coal\":\"4\"}}"})
            assertThrows(IOException.class,()->read(text),text);
    }
    @Test void unsafeBoundsAreRejected() {
        Map<String,String> invalid=Map.ofEntries(Map.entry("worldRadius","-1"),Map.entry("netherRadius","1501"),
                Map.entry("spawnProtectionRadius","0"),Map.entry("warImmunityDays","0"),Map.entry("maxAuctionListings","0"),
                Map.entry("auctionHours","2147483647"),Map.entry("teleportWarmupSeconds","0"),Map.entry("combatSeconds","-1"),
                Map.entry("globalDailySaleLimit","1"),Map.entry("nationCost","9223372036854775807"),Map.entry("auctionFeePercent","31"));
        for(var entry:invalid.entrySet())assertThrows(IOException.class,()->read("{\""+entry.getKey()+"\":"+entry.getValue()+"}"),entry.getKey());
    }
    @Test void badCatalogsAreRejected() {
        for(String prices:new String[]{"null","{}","[]","{\"minecraft:air\":1}","{\"other:coin\":2}",
                "{\"minecraft:coal\":0}","{\"minecraft:coal\":null}","{\"bad id\":4}"})
            assertThrows(IOException.class,()->read("{\"buyerPrices\":"+prices+"}"));
    }
    @Test void malformedOrNonObjectJsonIsRejected() {
        for(String text:new String[]{"null","[]","true","","{"})assertThrows(IOException.class,()->read(text));
    }
    @Test void oversizedConfigIsRejectedBeforeParsing() {
        assertThrows(IOException.class,()->read(" ".repeat(65537)));
    }
    @Test void validIntegerNotationAndPriceCatalogAreAccepted() throws Exception {
        var config=read("{\"mobilizationHours\":12.0,\"buyerPrices\":{\"minecraft:coal\":4}}");
        assertEquals(4L,config.buyerPrices.get("minecraft:coal"));
    }
}
