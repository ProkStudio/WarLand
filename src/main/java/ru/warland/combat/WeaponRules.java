package ru.warland.combat;

public final class WeaponRules {
    public static final int CAPACITY=30,SHOT_TICKS=10,RELOAD_TICKS=44;
    public static final double RANGE=80;
    public static final float DAMAGE=6;
    private WeaponRules() {}
    public static int ammunition(int stored){return Math.max(0,Math.min(CAPACITY,stored));}
    public static boolean mayFire(long now,long next,int ammunition,boolean reloading){return now>=next&&ammunition>0&&!reloading;}
    public static boolean sameWeapon(String expected,String actual,int slot,int currentSlot){return expected!=null&&!expected.isBlank()&&expected.equals(actual)&&slot==currentSlot;}
}
