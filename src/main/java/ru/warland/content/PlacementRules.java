package ru.warland.content;

/** Fail-closed spatial and collision decisions, independent of a loaded Minecraft world. */
public final class PlacementRules {
    private PlacementRules() {}
    public enum CellAction { PLACE, KEEP, STOP }
    public record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public boolean contains(int x,int y,int z) { return x>=minX && x<=maxX && y>=minY && y<=maxY && z>=minZ && z<=maxZ; }
        public boolean intersects(Bounds b) { return minX<=b.maxX && maxX>=b.minX && minY<=b.maxY && maxY>=b.minY && minZ<=b.maxZ && maxZ>=b.minZ; }
    }
    public static Bounds bounds(BlockPlan p,int x,int y,int z) {
        return new Bounds(Math.addExact(x,p.minX()),Math.addExact(y,p.minY()),Math.addExact(z,p.minZ()),
                Math.addExact(x,p.maxX()),Math.addExact(y,p.maxY()),Math.addExact(z,p.maxZ()));
    }
    public static CellAction cell(boolean permitted,boolean loaded,boolean air,boolean liquid,boolean existingMatches,boolean journaledJob) {
        if(!permitted || !loaded || liquid) return CellAction.STOP;
        if(air) return CellAction.PLACE;
        if(existingMatches && journaledJob) return CellAction.KEEP;
        return CellAction.STOP;
    }
    public static boolean validReason(String reason) { return reason!=null && reason.strip().length()>=8 && reason.length()<=160 && reason.codePoints().noneMatch(Character::isISOControl); }
    public static boolean isProtected(ContentData.Job j) { return !j.terminal(); }
    public static boolean ownerCapReached(ContentData data,String owner) {
        return data.jobs.values().stream().filter(j->j.owner.equals(owner) && !j.stage.equals("ABANDONED")).count()>=data.config.maxBuildingsPerPlayer;
    }
    public static String name(String plan) { return switch(plan) { case "depot_v1"->"Склад 9×7"; case "workshop_v1"->"Мастерская 11×9"; default->"Неизвестный чертёж"; }; }
}
