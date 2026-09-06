package ru.warland.content;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BuildingSiteRulesTest {
    private BlockPlan tiny() {
        return new BlockPlan.Builder("test").block(0,0,0,"minecraft:stone")
                .block(2,2,2,"minecraft:stone").build();
    }
    @Test void currentBlueprintsCheckEveryVoxelAndFoundationExactlyOnce() {
        for(var plan:Layouts.buildings().values()) {
            var seen=new HashSet<BlockPlan.Point>();
            assertTrue(BuildingSiteRules.allowed(plan,-20,70,-30,(x,y,z)-> {
                assertTrue(seen.add(new BlockPlan.Point(x,y,z)));return true;
            }));
            assertEquals(plan.width()*plan.depth()*(plan.height()+1),seen.size());
            assertTrue(seen.size()<=1024);
            for(int i=0;i<plan.volume();i++) {
                var p=plan.volumePoint(i);
                assertTrue(seen.contains(new BlockPlan.Point(-20+p.x(),70+p.y(),-30+p.z())));
            }
            for(int x=plan.minX();x<=plan.maxX();x++)for(int z=plan.minZ();z<=plan.maxZ();z++)
                assertTrue(seen.contains(new BlockPlan.Point(-20+x,69+plan.minY(),-30+z)));
        }
    }
    @Test void emptyInteriorStillRequiresPermission() {
        assertFalse(BuildingSiteRules.allowed(tiny(),0,70,0,(x,y,z)->!(x==1&&y==71&&z==1)));
    }
    @Test void foundationAndTopFarCornerCannotBypassPermission() {
        assertFalse(BuildingSiteRules.allowed(tiny(),0,70,0,(x,y,z)->y!=69));
        assertFalse(BuildingSiteRules.allowed(tiny(),0,70,0,(x,y,z)->!(x==2&&y==72&&z==2)));
    }
    @Test void buildingCannotCrossIntoUnclaimedOrForeignChunk() {
        var plan=Layouts.buildings().get("depot_v1");
        assertFalse(BuildingSiteRules.allowed(plan,15,70,15,(x,y,z)->(x>>4)==0&&(z>>4)==0));
        assertFalse(BuildingSiteRules.allowed(plan,-1,70,-1,(x,y,z)->(x>>4)==-1&&(z>>4)==-1));
        assertTrue(BuildingSiteRules.allowed(plan,-16,70,-16,(x,y,z)->(x>>4)==-1&&(z>>4)==-1));
    }
    @Test void permissionIsFreshAcrossDeferredStagesNotCached() {
        var authorized=new AtomicBoolean(true);
        BuildingSiteRules.PermissionAt check=(x,y,z)->authorized.get();
        assertTrue(BuildingSiteRules.allowed(tiny(),0,70,0,check));
        authorized.set(false);
        assertFalse(BuildingSiteRules.allowed(tiny(),0,70,0,check));
        authorized.set(true);
        assertTrue(BuildingSiteRules.allowed(tiny(),0,70,0,check));
    }
    @Test void rejectionShortCircuitsBeforeAnyFurtherChecks() {
        var calls=new AtomicInteger();
        assertFalse(BuildingSiteRules.allowed(tiny(),0,70,0,(x,y,z)-> {calls.incrementAndGet();return false;}));
        assertEquals(1,calls.get());
    }
    @Test void hugePlansFailClosedWithoutUnboundedServerThreadWork() {
        var calls=new AtomicInteger();
        assertFalse(BuildingSiteRules.allowed(Layouts.capital(),0,80,0,(x,y,z)-> {calls.incrementAndGet();return true;}));
        assertEquals(0,calls.get());
    }
    @Test void integerOverflowNeverWrapsIntoAnotherTerritory() {
        BuildingSiteRules.PermissionAt never=(x,y,z)-> {fail("Overflow must fail before permission lookup");return true;};
        assertFalse(BuildingSiteRules.allowed(tiny(),Integer.MAX_VALUE,70,0,never));
        assertFalse(BuildingSiteRules.allowed(tiny(),0,Integer.MIN_VALUE,0,never));
        assertFalse(BuildingSiteRules.allowed(tiny(),0,70,Integer.MAX_VALUE,never));
    }
    @Test void nullOrMissingPolicyCannotAuthorizeConstruction() {
        assertFalse(BuildingSiteRules.allowed(null,0,70,0,(x,y,z)->true));
        assertFalse(BuildingSiteRules.allowed(tiny(),0,70,0,null));
    }
}
