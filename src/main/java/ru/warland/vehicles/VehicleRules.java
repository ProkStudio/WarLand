package ru.warland.vehicles;

/** Conservative server simulation in blocks/tick. No client position is trusted. */
public final class VehicleRules {
    public static final int MAX_ACTIVE=16, MAX_RECORDS=512, MAX_OWNED=3, MAX_FUEL=6000, LEASE_TICKS=100;
    public static final double MAX_SPEED=.18, ACCELERATION=.012, TURN_DEGREES=4;
    private VehicleRules() {}
    public static double speed(double current,int throttle) {
        if(!Double.isFinite(current)||throttle < -1||throttle > 1)throw new IllegalArgumentException("Invalid motion");
        if(throttle==0)return Math.abs(current)<.02?0:current*.80;
        return Math.max(-MAX_SPEED/2,Math.min(MAX_SPEED,current+throttle*ACCELERATION));
    }
    public static double heading(double current,int steering) {
        if(!Double.isFinite(current)||steering < -1||steering > 1)throw new IllegalArgumentException("Invalid steering");
        return ((current+steering*TURN_DEGREES)%360+360)%360;
    }
    public static int reserveFuel(int available) {
        if(available<0||available>MAX_FUEL)throw new IllegalArgumentException("Invalid fuel");
        return Math.min(available,LEASE_TICKS);
    }
    public static boolean validReason(String reason) {
        return reason!=null&&reason.length()>=8&&reason.length()<=160&&reason.chars().noneMatch(Character::isISOControl);
    }
}
