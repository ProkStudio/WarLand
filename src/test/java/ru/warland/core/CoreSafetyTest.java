package ru.warland.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CoreSafetyTest {
 @Test void inclusiveSquare(){assertTrue(CoreSafety.withinSquare(128,-128,0,0,128));assertFalse(CoreSafety.withinSquare(129,0,0,0,128));assertFalse(CoreSafety.withinSquare(0,0,0,0,-1));}
 @Test void coordinateSubtractionDoesNotOverflow(){assertFalse(CoreSafety.withinSquare(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0,10));assertFalse(CoreSafety.withinSquare(Integer.MAX_VALUE,0,Integer.MIN_VALUE,0,Integer.MAX_VALUE));assertTrue(CoreSafety.withinSquare(Integer.MIN_VALUE,0,Integer.MIN_VALUE,0,0));}
 @Test void publicHubWarpsRemainPublic(){assertTrue(CoreSafety.warpAllowed("spawn",null,null));assertTrue(CoreSafety.warpAllowed("capital_market","a",null));}
 @Test void privateCapitalNeedsMemberAndCurrentClaim(){assertTrue(CoreSafety.warpAllowed("capital-a","a","a"));assertFalse(CoreSafety.warpAllowed("capital-a",null,"a"));assertFalse(CoreSafety.warpAllowed("capital-a","b","a"));assertFalse(CoreSafety.warpAllowed("capital-a","a","b"));assertFalse(CoreSafety.warpAllowed("capital-a","a",null));}
 @Test void blankWarpRejected(){assertFalse(CoreSafety.warpAllowed(null,null,null));assertFalse(CoreSafety.warpAllowed("",null,null));}
 @Test void cityCannotBeBuiltInWildernessOrForeignClaim(){assertFalse(CoreSafety.cityAllowed(true,false,"a",null,true));assertFalse(CoreSafety.cityAllowed(true,false,"a","b",true));assertFalse(CoreSafety.cityAllowed(true,false,null,"a",true));}
 @Test void cityNeedsReadinessRankAndSafeLocation(){assertTrue(CoreSafety.cityAllowed(true,false,"a","a",true));assertFalse(CoreSafety.cityAllowed(false,false,"a","a",true));assertFalse(CoreSafety.cityAllowed(true,true,"a","a",true));assertFalse(CoreSafety.cityAllowed(true,false,"a","a",false));}
}
