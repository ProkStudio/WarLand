package ru.warland.core;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public final class GameConfig {
    public int schemaVersion=1;
    public long startingBalance=1500,nationCost=1000,claimCost=100,warCost=5000;
    public int worldRadius=6000,netherRadius=1500,spawnProtectionRadius=112;
    public long mobilizationHours=12,warHours=72,warImmunityDays=7;
    public int warHourMoscow=19,warWindowHours=2;
    public int maxAuctionListings=8,auctionHours=48,auctionFeePercent=3;
    public int personalDailySaleLimit=1024,globalDailySaleLimit=16384,maxTaxPercent=15;
    public int teleportWarmupSeconds=8,combatSeconds=30;
    public boolean requireOnlineModeForPublic=true;
    public boolean enableAuction=false;
    public boolean enableBuyer=false;
    public boolean enableWarCapture=false;
    public Map<String,Long> buyerPrices=new LinkedHashMap<>(Map.of("minecraft:cobblestone",1L,"minecraft:coal",4L,"minecraft:iron_ingot",10L,"minecraft:gold_ingot",16L,"minecraft:diamond",75L,"minecraft:wheat",2L,"minecraft:oak_log",3L));
    public static GameConfig load(Path path)throws IOException{
        Gson g=new GsonBuilder().setPrettyPrinting().create();GameConfig c;
        if(Files.exists(path)){c=g.fromJson(Files.readString(path),GameConfig.class);}else{c=new GameConfig();Files.createDirectories(path.getParent());Files.writeString(path,g.toJson(c));}
        if(c==null||c.schemaVersion!=1||c.startingBalance<0||c.startingBalance>1_000_000||c.nationCost<1||c.claimCost<1||c.warCost<1||c.mobilizationHours!=12||c.warHours<24||c.warHours>168||c.warWindowHours<1||c.warWindowHours>8||c.warHourMoscow<0||c.warHourMoscow>23||c.personalDailySaleLimit<1||c.globalDailySaleLimit<c.personalDailySaleLimit||c.maxTaxPercent<0||c.maxTaxPercent>15||c.auctionFeePercent<0||c.auctionFeePercent>30||c.buyerPrices==null||c.buyerPrices.values().stream().anyMatch(v->v==null||v<1||v>100000))throw new IOException("Invalid WarLand config: refusing unsafe startup");
        return c;
    }
}
