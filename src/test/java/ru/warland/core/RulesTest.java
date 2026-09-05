package ru.warland.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class RulesTest {
 @Test void nationNames(){assertTrue(Rules.validNation("Север_01"));assertFalse(Rules.validNation("<script>"));assertFalse(Rules.validNation("ab"));}
 @Test void prices(){assertEquals(100,Rules.price(100,0,1000));assertEquals(25,Rules.price(100,1000,1000));assertEquals(25,Rules.price(100,99999,1000));assertEquals(1,Rules.price(1,1000,1000));}
 @Test void quantity(){assertEquals(160,Rules.total(10,16));assertThrows(IllegalArgumentException.class,()->Rules.total(10,-1));assertThrows(ArithmeticException.class,()->Rules.total(Long.MAX_VALUE,2));}
 @Test void limits(){assertEquals(12,Rules.claimLimit(1,0));assertEquals(256,Rules.claimLimit(1000,1000));}
 @Test void warWindow(){long now=ZonedDateTime.of(2026,9,5,19,0,0,0,Rules.ZONE).toInstant().toEpochMilli();assertTrue(Rules.combatWindow(now,now-1,now+1000,19,2));assertFalse(Rules.combatWindow(now,now+1,now+1000,19,2));assertFalse(Rules.combatWindow(now,now-1000,now,19,2));assertFalse(Rules.combatWindow(now,now-1,now+1000,21,2));}
 @Test void moscowDay(){assertEquals("2026-09-06",Rules.day(Instant.parse("2026-09-05T21:00:00Z").toEpochMilli()));}
}
