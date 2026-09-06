package ru.warland.combat;

import org.junit.jupiter.api.Test;
import ru.warland.enchantments.EnchantmentRules;
import static org.junit.jupiter.api.Assertions.*;
import static ru.warland.enchantments.EnchantmentRules.Focus.*;

class WeaponProfileTest {
 @Test void allFiveProfilesAreDistinctAndBounded(){var profiles=WeaponProfile.defaults();assertEquals(5,profiles.size());profiles.forEach((id,p)->{assertEquals(id,p.id());assertTrue(p.damage()*p.pellets()<=18);assertTrue(p.range()<=96);assertTrue(p.shotTicks()>=3);});}
 @Test void ammunitionCannotExceedCapacity(){for(var p:WeaponProfile.defaults().values()){assertEquals(0,p.ammo(-1));assertEquals(p.capacity(),p.ammo(Integer.MAX_VALUE));}}
 @Test void falloffAndInvalidDistanceNeverIncreaseDamage(){for(var p:WeaponProfile.defaults().values()){assertEquals(p.damage(),p.damageAt(0));assertTrue(p.damageAt(p.range())<p.damage());assertEquals(0,p.damageAt(p.range()+1));assertEquals(0,p.damageAt(Double.NaN));}}
 @Test void invalidProfilesAreRejected(){assertThrows(IllegalArgumentException.class,()->new WeaponProfile("bad","name",10,1,10,50,6,0,1));assertThrows(IllegalArgumentException.class,()->new WeaponProfile("bad","name",10,3,10,50,12,0,6));}
 @Test void specializationsHaveActualTradeoffs(){assertTrue(EnchantmentRules.finalSpread(.02,STEADY,2)<.02);assertTrue(EnchantmentRules.finalReload(40,STEADY,2)>40);assertTrue(EnchantmentRules.finalSpread(.02,QUICKLOAD,2)>.02);assertTrue(EnchantmentRules.finalReload(40,QUICKLOAD,2)<40);}
 @Test void invalidSpecializationAndLevelsAreClamped(){assertEquals(NONE,EnchantmentRules.parse("UNKNOWN"));assertEquals(NONE,EnchantmentRules.parse(null));assertEquals(0,EnchantmentRules.level(-10));assertEquals(2,EnchantmentRules.level(999));}
}
