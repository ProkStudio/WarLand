package ru.warland.enchantments;

/** Two mutually exclusive gun specializations. No damage, economy or invulnerability multipliers. */
public final class EnchantmentRules {
    public enum Focus { NONE, STEADY, QUICKLOAD }
    private EnchantmentRules() {}
    public static Focus parse(String value){try{return Focus.valueOf(value);}catch(IllegalArgumentException|NullPointerException bad){return Focus.NONE;}}
    public static int level(int stored){return Math.max(0,Math.min(2,stored));}
    public static double spread(double base,Focus focus,int level){return base*(focus==Focus.STEADY?1-.15*level(level):1);}
    public static int reload(int base,Focus focus,int level){return Math.max(10,(int)Math.ceil(base*(focus==Focus.QUICKLOAD?1-.10*level(level):1)));}
    // Trade-offs prevent a strict-best choice: steady handling costs reload, quickload costs precision.
    public static double finalSpread(double base,Focus focus,int level){return spread(base,focus,level)*(focus==Focus.QUICKLOAD?1+.10*level(level):1);}
    public static int finalReload(int base,Focus focus,int level){return (int)Math.ceil(reload(base,focus,level)*(focus==Focus.STEADY?1+.10*level(level):1));}
}
