package ru.warland.combat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WeaponRulesTest {
 @Test void magazineBounds(){assertEquals(0,WeaponRules.ammunition(-1));assertEquals(0,WeaponRules.ammunition(Integer.MIN_VALUE));assertEquals(30,WeaponRules.ammunition(Integer.MAX_VALUE));assertEquals(19,WeaponRules.ammunition(19));}
 @Test void firingIsServerRateLimited(){assertFalse(WeaponRules.mayFire(9,10,30,false));assertTrue(WeaponRules.mayFire(10,10,30,false));assertFalse(WeaponRules.mayFire(10,10,0,false));assertFalse(WeaponRules.mayFire(10,10,30,true));}
 @Test void reloadDoesNotFollowOtherWeapons(){assertTrue(WeaponRules.sameWeapon("a","a",0,0));assertFalse(WeaponRules.sameWeapon("a","b",0,0));assertFalse(WeaponRules.sameWeapon("a","a",0,1));assertFalse(WeaponRules.sameWeapon("","",0,0));assertFalse(WeaponRules.sameWeapon(null,"a",0,0));}
}
