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

    public void validate() throws IOException {
        if(schemaVersion!=1 || !between(startingBalance,0,1_000_000)
                || !between(nationCost,1,1_000_000_000) || !between(claimCost,1,1_000_000_000)
                || !between(warCost,1,1_000_000_000) || mobilizationHours!=12
                || !between(warHours,24,168) || !between(warImmunityDays,5,7)
                || !between(warWindowHours,1,8) || !between(warHourMoscow,0,23)
                || !between(worldRadius,256,8000) || !between(netherRadius,128,1500)
                || !between(spawnProtectionRadius,1,1024)
                || !between(maxAuctionListings,1,32) || !between(auctionHours,1,168)
                || !between(auctionFeePercent,0,30) || !between(maxTaxPercent,0,15)
                || !between(personalDailySaleLimit,1,1_000_000)
                || !between(globalDailySaleLimit,personalDailySaleLimit,10_000_000)
                || !between(teleportWarmupSeconds,1,60) || !between(combatSeconds,1,300)
                || buyerPrices==null || buyerPrices.isEmpty() || buyerPrices.size()>128
                || buyerPrices.entrySet().stream().anyMatch(e->e.getKey()==null
                    || !e.getKey().matches("minecraft:[a-z0-9_]+") || e.getKey().equals("minecraft:air")
                    || e.getValue()==null || !between(e.getValue(),1,100000))) {
            throw new IOException("Invalid WarLand config: refusing unsafe startup");
        }
    }
    private static boolean between(long value,long min,long max) { return value>=min && value<=max; }

    public static GameConfig load(Path path) throws IOException {
        Gson gson=new GsonBuilder().setPrettyPrinting().create();
        GameConfig config;
        if(Files.exists(path)) {
            if(Files.size(path)>65_536) throw new IOException("WarLand config exceeds size limit");
            try {
                JsonElement root=JsonParser.parseString(Files.readString(path));
                if(!root.isJsonObject()) throw new IOException("WarLand config must be a JSON object");
                JsonObject object=root.getAsJsonObject();
                // Validate types before Gson coercion (e.g. 1.5 -> int or string -> boolean).
                for(var field:GameConfig.class.getFields()) {
                    if(!object.has(field.getName())) continue; // omitted values use safe defaults
                    JsonElement value=object.get(field.getName());
                    if(field.getType()==boolean.class) {
                        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                            throw new IOException("Invalid boolean configuration field");
                    } else if(field.getType()==int.class || field.getType()==long.class) {
                        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                            throw new IOException("Invalid integer configuration field");
                        long number=value.getAsBigDecimal().longValueExact();
                        if(field.getType()==int.class && (number<Integer.MIN_VALUE || number>Integer.MAX_VALUE))
                            throw new IOException("Integer configuration overflow");
                    }
                }
                if(object.has("buyerPrices")) {
                    JsonElement prices=object.get("buyerPrices");
                    if(!prices.isJsonObject()) throw new IOException("Invalid buyer price catalog");
                    for(var entry:prices.getAsJsonObject().entrySet()) {
                        JsonElement value=entry.getValue();
                        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                            throw new IOException("Invalid buyer price");
                        value.getAsBigDecimal().longValueExact();
                    }
                }
                config=gson.fromJson(object,GameConfig.class);
            } catch(JsonParseException | ArithmeticException | IllegalStateException error) {
                throw new IOException("Malformed WarLand config: original file preserved",error);
            }
        } else {
            config=new GameConfig();
            config.validate();
            Files.createDirectories(path.toAbsolutePath().getParent());
            // Never overwrite a concurrently-created config with defaults.
            Files.writeString(path,gson.toJson(config),StandardOpenOption.CREATE_NEW);
        }
        config.validate();
        return config;
    }
}
