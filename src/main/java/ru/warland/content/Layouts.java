package ru.warland.content;

import java.util.*;

/** Original procedural architecture, vanilla blocks only. Coordinates are relative to the floor. */
public final class Layouts {
    private Layouts() {}
    public static final String CAPITAL_ID = "capital_v1";
    public static final int CAPITAL_Y = 80;
    public static final int CAPITAL_RADIUS = 112;
    public record Zone(String id, String name, int x, int z, String icon, String accent) {}
    public static final List<Zone> ZONES = List.of(
            new Zone("market", "Торговый квартал", -43,-27,"minecraft:emerald","minecraft:oxidized_copper"),
            new Zone("quests", "Штаб заданий",43,-27,"minecraft:book","minecraft:cyan_concrete"),
            new Zone("transport", "Транспортный терминал",-43,27,"minecraft:compass","minecraft:lime_terracotta"),
            new Zone("training", "Учебный полигон",43,27,"minecraft:target","minecraft:orange_terracotta"));
    public static final List<BlockPlan.Point> CIRCUIT = List.of(
            new BlockPlan.Point(-27,1,0),new BlockPlan.Point(-43,1,-27),new BlockPlan.Point(0,1,-27),
            new BlockPlan.Point(43,1,-27),new BlockPlan.Point(27,1,0),new BlockPlan.Point(43,1,27),
            new BlockPlan.Point(0,1,27),new BlockPlan.Point(-43,1,27));

    public static BlockPlan capital() {
        var b = new BlockPlan.Builder(CAPITAL_ID);
        // Beveled island, 193 x 193. No scheduled AIR cells, terrain clearing or imported assets.
        for(int z=-96;z<=96;z++) for(int x=-96;x<=96;x++) {
            if(Math.abs(x)+Math.abs(z)>157) continue;
            boolean edge = Math.abs(x)==96 || Math.abs(z)==96 || Math.abs(x)+Math.abs(z)==157;
            b.block(x,-1,z,"minecraft:polished_andesite");
            String surface = edge ? "minecraft:polished_deepslate" : "minecraft:smooth_stone";
            if(Math.abs(x)<=3 || Math.abs(z)<=3 || (Math.abs(x)>=25 && Math.abs(x)<=29) || (Math.abs(z)>=25 && Math.abs(z)<=29)) surface="minecraft:polished_deepslate";
            if((Math.abs(x)==4 || Math.abs(z)==4) && Math.abs(x)+Math.abs(z)>22) surface="minecraft:white_concrete";
            int r=x*x+z*z;
            if(r<=20*20) surface = r>=18*18 ? "minecraft:quartz_block" : "minecraft:smooth_quartz";
            if(r<13*13 && (Math.abs(x)==8 || Math.abs(z)==8)) surface="minecraft:cyan_terracotta";
            b.block(x,0,z,surface);
            if(edge) b.block(x,1,z,"minecraft:polished_deepslate_wall");
        }
        // Dry, walkable central landmark; deliberately no fluids, beacon, redstone, or entities.
        b.box(-3,1,-9,3,1,-3,"minecraft:quartz_block");
        b.box(-2,2,-8,2,2,-4,"minecraft:smooth_quartz");
        b.box(-1,3,-7,1,12,-5,"minecraft:oxidized_copper");
        b.box(-2,7,-8,-2,11,-4,"minecraft:quartz_pillar");
        b.box(2,4,-8,2,9,-4,"minecraft:quartz_pillar");
        b.box(-1,13,-7,1,13,-5,"minecraft:sea_lantern");
        b.box(-2,14,-8,2,14,-4,"minecraft:smooth_quartz_slab");

        pavilion(b,-73,-64,48,31,"minecraft:oxidized_copper", false);
        pavilion(b,24,-63,49,32,"minecraft:cyan_concrete", false);
        pavilion(b,-73,32,48,33,"minecraft:cyan_concrete", true);
        // Four low market booths, not villager/NPC farms.
        for(int x=-66;x<=-33;x+=11) {
            b.box(x,1,-55,x+6,1,-53,"minecraft:spruce_planks");
            b.box(x,4,-56,x+6,4,-52,"minecraft:white_concrete");
            b.block(x,2,-54,"minecraft:barrel");
        }
        b.box(34,1,-55,62,1,-51,"minecraft:smooth_quartz");
        for(int x=34;x<=62;x+=7) b.box(x,2,-59,x+3,4,-59,"minecraft:bookshelf");
        // Transport gates are architectural markers, not functioning nether/end portals.
        for(int x=-63;x<=-35;x+=14) {
            b.box(x,1,48,x,5,48,"minecraft:quartz_pillar");
            b.box(x+7,1,48,x+7,5,48,"minecraft:quartz_pillar");
            b.box(x,6,48,x+7,6,48,"minecraft:oxidized_copper");
            b.box(x+1,0,46,x+6,0,51,"minecraft:lime_terracotta");
        }
        // East-south training court, no persistent mobs or destructible practice vehicles.
        b.box(33,0,34,75,0,67,"minecraft:orange_terracotta");
        b.box(32,1,68,76,4,68,"minecraft:polished_deepslate");
        for(int x=39;x<=65;x+=13) {
            b.block(x,2,67,"minecraft:target");
            b.box(x-2,0,37,x+2,0,62,"minecraft:smooth_stone");
            b.box(x-2,1,62,x+2,1,64,"minecraft:polished_andesite");
        }
        for(int z=37;z<=61;z+=6) b.box(71,1,z,73,1,z+2,"minecraft:quartz_block");
        // Small civic colonnade and an empty demonstration apron: no false season/vehicle content.
        pavilion(b,-88,-18,18,36,"minecraft:quartz_block",true);
        pavilion(b,70,-18,18,36,"minecraft:oxidized_copper",true);
        for(int z=-12;z<=12;z+=8) b.box(-86,1,z,-84,4,z+2,"minecraft:chiseled_quartz_block");
        b.box(73,0,-13,85,0,13,"minecraft:polished_andesite");
        // Clear color-coded pedestrian connections and low-cost terminal blocks.
        for(Zone zone : ZONES) {
            int from=Math.min(zone.x(),0), to=Math.max(zone.x(),0);
            b.box(from,0,zone.z()-1,to,0,zone.z()+1,zone.accent());
            b.block(zone.x(),1,zone.z(),"minecraft:lodestone");
            b.block(zone.x(),2,zone.z(),"minecraft:sea_lantern");
            b.block(zone.x(),3,zone.z(),"minecraft:dark_oak_sign[rotation=0]");
        }
        // Trees are explicit persistent leaves; no saplings, decay or mob spawning infrastructure.
        int[][] trees={{-82,-76},{-62,-79},{-40,-80},{-17,-68},{16,-71},{40,-79},{66,-78},{82,-74},
                {-82,76},{-61,80},{-39,79},{-16,68},{16,70},{40,79},{64,79},{82,74}};
        for(int[] p : trees) tree(b,p[0],p[1]);
        for(int x=-84;x<=84;x+=14) for(int z : new int[]{-23,23}) {
            b.block(x,0,z,"minecraft:sea_lantern");
            if(Math.abs(x)>33) {
                b.box(x,1,z,x+3,1,z,"minecraft:stone_brick_slab");
            }
        }
        for(int z=-84;z<=84;z+=14) for(int x : new int[]{-23,23}) b.block(x,0,z,"minecraft:sea_lantern");
        return b.build();
    }
    private static void pavilion(BlockPlan.Builder b,int x,int z,int w,int d,String trim,boolean open) {
        b.box(x,0,z,x+w-1,0,z+d-1,"minecraft:smooth_quartz");
        b.box(x-1,7,z-1,x+w,7,z+d,trim);
        b.box(x,8,z,x+w-1,8,z+d-1,"minecraft:smooth_quartz_slab");
        for(int px=x;px<x+w;px+=8) for(int pz : new int[]{z,z+d-1}) b.box(px,1,pz,px,6,pz,"minecraft:quartz_pillar");
        b.box(x+w-1,1,z,x+w-1,6,z,"minecraft:quartz_pillar");
        b.box(x+w-1,1,z+d-1,x+w-1,6,z+d-1,"minecraft:quartz_pillar");
        if(!open) {
            b.box(x+1,1,z,x+w-2,5,z,"minecraft:light_blue_stained_glass");
            b.box(x,1,z+1,x,5,z+d-2,"minecraft:light_blue_stained_glass");
            b.box(x+w-1,1,z+1,x+w-1,5,z+d-2,"minecraft:light_blue_stained_glass");
        }
        for(int px=x+4;px<x+w-2;px+=8) b.block(px,6,z+d/2,"minecraft:sea_lantern");
    }
    private static void tree(BlockPlan.Builder b,int x,int z) {
        b.box(x-2,1,z-2,x+2,1,z+2,"minecraft:quartz_block");
        b.box(x,2,z,x,6,z,"minecraft:oak_log");
        b.box(x-2,5,z-2,x+2,7,z+2,"minecraft:oak_leaves[persistent=true]");
        b.box(x-1,8,z-1,x+1,8,z+1,"minecraft:oak_leaves[persistent=true]");
    }
    public static Map<String, BlockPlan> buildings() {
        Map<String, BlockPlan> result = new LinkedHashMap<>();
        result.put("depot_v1",building("depot_v1",9,7,"minecraft:spruce_planks",false));
        result.put("workshop_v1",building("workshop_v1",11,9,"minecraft:bricks",true));
        return Collections.unmodifiableMap(result);
    }
    private static BlockPlan building(String id,int w,int d,String wall,boolean workshop) {
        var b=new BlockPlan.Builder(id);
        b.box(0,0,0,w-1,0,d-1,"minecraft:stone_bricks");
        for(int x : new int[]{0,w-1}) for(int z : new int[]{0,d-1}) b.box(x,1,z,x,4,z,"minecraft:oak_log");
        b.box(1,1,0,w-2,3,0,wall);
        b.box(1,1,d-1,w-2,3,d-1,wall);
        b.box(0,1,1,0,3,d-2,wall);
        b.box(w-1,1,1,w-1,3,d-2,wall);
        b.omit(w/2,1,0,w/2+1,3,0); // permanently open, two-wide entrance
        b.box(0,2,2,0,3,d-3,"minecraft:glass");
        b.box(w-1,2,2,w-1,3,d-3,"minecraft:glass");
        b.box(0,4,0,w-1,4,d-1,"minecraft:spruce_planks");
        b.box(0,5,0,w-1,5,d-1,"minecraft:spruce_slab");
        b.block(1,1,d-2,"minecraft:barrel");
        b.block(2,1,d-2,"minecraft:crafting_table");
        b.block(w-2,3,d-2,"minecraft:glowstone");
        if(workshop) { b.block(w-2,1,d-2,"minecraft:smithing_table"); b.block(w-3,1,d-2,"minecraft:stonecutter"); }
        return b.build();
    }
}
