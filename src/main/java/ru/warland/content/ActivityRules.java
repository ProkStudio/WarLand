package ru.warland.content;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

/** Pure deterministic rules. Rewards and claim markers are written together in the content state. */
public final class ActivityRules {
    private ActivityRules() {}
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    public static final Set<String> DAILY = Set.of("tour","walk","survey");
    public enum Claim { GRANTED, ALREADY, INCOMPLETE, WEEKLY_CAP }
    public record EventWindow(Instant start, Instant end) {
        public boolean active(Instant now) { return !now.isBefore(start) && now.isBefore(end); }
    }
    public static long day(Instant now) { return now.atZone(ZONE).toLocalDate().toEpochDay(); }
    public static long week(Instant now) { return now.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay(); }
    public static EventWindow nextOrCurrentEvent(Instant now) {
        ZonedDateTime local=now.atZone(ZONE);
        LocalDate saturday=local.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        ZonedDateTime start=saturday.atTime(18,0).atZone(ZONE);
        if(!now.isBefore(start.plusHours(1).toInstant())) start=start.plusWeeks(1);
        return new EventWindow(start.toInstant(),start.plusHours(1).toInstant());
    }
    /** Never roll backwards after a clock correction: rollback cannot reset claim markers. */
    public static void roll(ContentData.Profile p, Instant now) {
        long day=day(now),week=week(now);
        if(day>p.day) { p.day=day; p.zones.clear(); p.survey.clear(); p.claimed.clear(); p.walked=0; }
        if(week>p.week) { p.week=week; p.weekReputation=0; }
        if(week>p.eventWeek) {
            p.eventWeek=week; p.checkpoint=0; p.eventStarted=0; p.eventLastCheckpoint=0; p.eventWalked=0; p.eventClaimed=false;
        }
    }
    public static boolean completed(ContentData.Profile p,String quest) {
        return switch(quest) { case "tour"->p.zones.size()==4; case "walk"->p.walked>=600; case "survey"->p.survey.size()>=6; default->false; };
    }
    public static int reward(String quest) { return switch(quest) { case "tour"->12; case "walk"->16; case "survey"->18; default->throw new IllegalArgumentException("Unknown quest"); }; }
    public static Claim claim(ContentData.Profile p,String quest,int weeklyCap) {
        if(!DAILY.contains(quest) || !completed(p,quest)) return Claim.INCOMPLETE;
        if(p.claimed.contains(quest)) return Claim.ALREADY;
        if(p.weekReputation+reward(quest)>weeklyCap) return Claim.WEEKLY_CAP;
        p.claimed.add(quest); grant(p,reward(quest)); return Claim.GRANTED;
    }
    public static Claim claimEvent(ContentData.Profile p,Instant now,int weeklyCap) {
        if(p.eventClaimed) return Claim.ALREADY;
        if(p.eventWeek!=week(now) || !nextOrCurrentEvent(now).active(now) || p.checkpoint!=8
                || p.eventStarted<=0 || now.toEpochMilli()-p.eventStarted<90_000 || p.eventWalked<200) return Claim.INCOMPLETE;
        if(p.weekReputation+25>weeklyCap) return Claim.WEEKLY_CAP;
        p.eventClaimed=true; grant(p,25); return Claim.GRANTED;
    }
    private static void grant(ContentData.Profile p,int amount) {
        p.reputation=Math.min(1_000_000,p.reputation+amount); p.weekReputation=Math.addExact(p.weekReputation,amount);
    }
    public static String rank(int rep) {
        if(rep>=500) return "Старожил"; if(rep>=200) return "Мастер"; if(rep>=80) return "Горожанин"; return "Гость столицы";
    }
    public static double acceptedMovement(double horizontal,boolean grounded,boolean creative,boolean spectator,boolean mounted) {
        if(!Double.isFinite(horizontal) || horizontal<0.1 || horizontal>12 || !grounded || creative || spectator || mounted) return 0;
        return horizontal;
    }
}
