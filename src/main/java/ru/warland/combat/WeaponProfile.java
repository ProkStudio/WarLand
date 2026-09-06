package ru.warland.combat;

import java.util.Map;

/** Arcade tuning only; all distances, damage and firing clocks are server authoritative. */
public record WeaponProfile(String id,String name,int capacity,int shotTicks,int reloadTicks,double range,float damage,double spread,int pellets) {
    public WeaponProfile {
        if(id==null||!id.matches("[a-z0-9_]{1,32}")||name==null||name.isBlank()||name.length()>80||capacity<1||capacity>60||shotTicks<3||shotTicks>60||reloadTicks<10||reloadTicks>200||!Double.isFinite(range)||range<4||range>96||!Float.isFinite(damage)||damage<=0||damage>12||!Double.isFinite(spread)||spread<0||spread>.16||pellets<1||pellets>6||damage*pellets>18)throw new IllegalArgumentException("Invalid weapon profile");
    }
    public int ammo(int stored){return Math.max(0,Math.min(capacity,stored));}
    public float damageAt(double distance){
        if(!Double.isFinite(distance)||distance<0||distance>range)return 0;
        double fraction=Math.max(0,(distance-range*.4)/(range*.6));
        return (float)(damage*(1-.55*fraction));
    }
    public static Map<String,WeaponProfile> defaults(){return Map.of(
        "ak74",new WeaponProfile("ak74","АК-74",30,10,44,80,6,.018,1),
        "sidearm",new WeaponProfile("sidearm","Дозор · пистолет",12,8,28,40,4,.022,1),
        "smg",new WeaponProfile("smg","Вихрь · ПП",24,4,36,32,3,.040,1),
        "marksman",new WeaponProfile("marksman","Горизонт · винтовка",8,24,56,96,10,.008,1),
        "shotgun",new WeaponProfile("shotgun","Рубеж · дробовик",6,22,60,24,2.5f,.11,6));}
}
