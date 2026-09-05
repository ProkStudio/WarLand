package ru.warland.core;

/** Pure policy helpers shared by live checks and regression tests. */
public final class CoreSafety {
    private CoreSafety() {}
    public static boolean withinSquare(int x,int z,int centerX,int centerZ,int radius) {
        return radius>=0 && Math.abs((long)x-centerX)<=radius && Math.abs((long)z-centerZ)<=radius;
    }
    public static boolean warpAllowed(String warp,String memberNation,String targetClaim) {
        if(warp==null||warp.isBlank())return false;
        if(!warp.startsWith("capital-"))return true;
        return memberNation!=null && warp.equals("capital-"+memberNation) && memberNation.equals(targetClaim);
    }
    public static boolean cityAllowed(boolean ready,boolean forbidden,String memberNation,String claim,boolean rankAllowsBuild) {
        return ready && !forbidden && memberNation!=null && memberNation.equals(claim) && rankAllowsBuild;
    }
}
