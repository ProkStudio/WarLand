package ru.warland.content;

import java.util.*;

/** All durable content metadata belongs to ONE versioned state value, content / state.v1. */
public final class ContentData {
    public int schema = 1;
    public long revision;
    public Config config = new Config();
    public Capital capital = new Capital();
    public Map<String, Job> jobs = new LinkedHashMap<>();
    public Map<String, Profile> profiles = new LinkedHashMap<>();

    public static final class Config {
        public int version = 1;
        public int placementBudget = 64;
        public int scanBudget = 512;
        public int tickMicros = 1500;
        public int maxActiveJobs = 4;
        public int maxBuildingsPerPlayer = 3;
        public int maxJobs = 128;
        public int weeklyReputationCap = 120;
        public boolean buildingPurchasesEnabled = false;
        public long depotCost = 600;
        public long workshopCost = 1000;
        public void validate() {
            if(version!=1 || placementBudget<8 || placementBudget>256 || scanBudget<64 || scanBudget>4096
                    || tickMicros<250 || tickMicros>4000 || maxActiveJobs<1 || maxActiveJobs>8
                    || maxBuildingsPerPlayer<1 || maxBuildingsPerPlayer>10 || maxJobs<8 || maxJobs>1000
                    || weeklyReputationCap<30 || weeklyReputationCap>300 || depotCost<1 || depotCost>1_000_000
                    || workshopCost<depotCost || workshopCost>1_000_000) throw new IllegalArgumentException("Invalid content config v1");
        }
        public long price(String plan) {
            return switch(plan) { case "depot_v1" -> depotCost; case "workshop_v1" -> workshopCost;
                default -> throw new IllegalArgumentException("Unknown blueprint"); };
        }
    }
    public static final class Capital {
        public String plan = Layouts.CAPITAL_ID;
        public String hash = "";
        public String stage = "NEW";
        public int cursor;
        public String problem = "";
    }
    public static final class Job {
        public String id;
        public String owner;
        public String world;
        public String plan;
        public String hash;
        public int x,y,z;
        public int cursor;
        public int batchEnd;
        public String stage = "CHARGING";
        public boolean paid;
        public boolean materialsHeld;
        public long price;
        public long createdAt;
        public String problem = "";
        public boolean terminal() { return stage.equals("DONE") || stage.equals("ABANDONED"); }
        public String operationId() { return "content:building:"+id+":payment:v1"; }
    }
    public static final class Profile {
        public long day = -1;
        public long week = -1;
        public int reputation;
        public int weekReputation;
        public Set<String> zones = new LinkedHashSet<>();
        public Set<Long> survey = new LinkedHashSet<>();
        public Set<String> claimed = new LinkedHashSet<>();
        public double walked;
        public long eventWeek = -1;
        public int checkpoint;
        public long eventStarted;
        public long eventLastCheckpoint;
        public double eventWalked;
        public boolean eventClaimed;
        public boolean tutorialSkipped;
    }
    public Profile profile(UUID id) { return profiles.computeIfAbsent(id.toString(), ignored -> new Profile()); }
    public void validate() {
        if(schema!=1 || revision<0 || config==null || capital==null || jobs==null || profiles==null) throw new IllegalArgumentException("Unsupported/corrupt content state");
        config.validate();
        if(jobs.size()>1000 || profiles.size()>10_000) throw new IllegalArgumentException("Content state limit");
        if(capital.cursor<0 || capital.cursor>150_000 || !Layouts.CAPITAL_ID.equals(capital.plan)
                || capital.hash==null || capital.problem==null || !Set.of("NEW","SCANNING","BUILDING","READY","PAUSED").contains(capital.stage)) throw new IllegalArgumentException("Invalid capital state");
        for(var entry:jobs.entrySet()) {
            Job job=entry.getValue();
            UUID.fromString(entry.getKey()); UUID.fromString(job.owner);
            if(!entry.getKey().equals(job.id) || !Set.of("depot_v1","workshop_v1").contains(job.plan)
                    || job.world==null || !job.world.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || job.hash==null || job.problem==null || Math.abs((long)job.x)>29_000_000L || Math.abs((long)job.z)>29_000_000L
                    || job.y < -64 || job.y > 310 || job.cursor<0 || job.batchEnd<job.cursor || job.batchEnd>10_000
                    || job.price<1 || job.price>1_000_000 || !Set.of("CHARGING","PAYMENT_REVIEW","WAITING_MATERIALS","MATERIAL_INTENT","MATERIAL_REVIEW","PLACING","BATCH","PAUSED","RECOVERY","DONE","ABANDONED").contains(job.stage)) throw new IllegalArgumentException("Invalid building job");
            if(job.materialsHeld && !job.paid) throw new IllegalArgumentException("Materials without payment");
            if(Set.of("PLACING","BATCH","DONE").contains(job.stage) && (!job.paid || !job.materialsHeld)) throw new IllegalArgumentException("Unfunded placement");
        }
        for(var entry:profiles.entrySet()) {
            UUID.fromString(entry.getKey());
            Profile p=entry.getValue();
            if(p==null || p.reputation<0 || p.reputation>1_000_000 || p.weekReputation<0 || p.weekReputation>1_000_000
                    || p.zones==null || p.zones.size()>4 || p.survey==null || p.survey.size()>8 || p.claimed==null || p.claimed.size()>3
                    || !Double.isFinite(p.walked) || p.walked<0 || p.walked>1000 || p.checkpoint<0 || p.checkpoint>8
                    || !Double.isFinite(p.eventWalked) || p.eventWalked<0 || p.eventWalked>5000) throw new IllegalArgumentException("Invalid activity state");
        }
    }
    /** Fail closed after restart. Never replay inventory removal or infer a world/DB atomic commit. */
    public void quarantineInterruptedJobs() {
        for(Job job:jobs.values()) {
            switch(job.stage) {
                case "CHARGING" -> { job.stage="PAYMENT_REVIEW"; job.problem="Платёж требует сверки по ID операции."; }
                case "MATERIAL_INTENT" -> { job.stage="MATERIAL_REVIEW"; job.problem="Нельзя определить сохранность изъятых предметов. Нужна ручная сверка."; }
                case "PLACING","BATCH" -> { job.stage="RECOVERY"; job.problem="Рестарт: сверить блоки и инвентарь, затем подтвердить продолжение."; }
                default -> { }
            }
        }
    }
}
