package ru.warland.core;

import java.time.*;

public final class Rules {
    public static final ZoneId ZONE=ZoneId.of("Europe/Moscow");
    private Rules(){}
    public static boolean validNation(String name){return name!=null&&name.matches("[\\p{L}0-9_\\-]{3,20}");}
    public static long total(long price,int quantity){if(price<=0||quantity<1||quantity>2304)throw new IllegalArgumentException("Invalid quantity/price");return Math.multiplyExact(price,quantity);}
    public static int claimLimit(int active,int level){return (int)Math.min(256L,9L+3L*Math.max(0,active)+9L*Math.max(0,level));}
    public static long price(long base,int sold,int quota){if(base<=0||quota<=0)throw new IllegalArgumentException();long percent=Math.max(25,100-75L*Math.max(0,sold)/quota);return Math.max(1,(base/100)*percent+(base%100)*percent/100);}
    public static boolean combatWindow(long now,long starts,long ends,int hour,int duration){if(now<starts||now>=ends)return false;int h=Instant.ofEpochMilli(now).atZone(ZONE).getHour();return Math.floorMod(h-hour,24)<duration;}
    public static String day(long now){return Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate().toString();}
}
