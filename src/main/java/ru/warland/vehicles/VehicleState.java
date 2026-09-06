package ru.warland.vehicles;

import java.util.*;

/** Persisted truth; entities are disposable projections, never ownership tokens. */
public final class VehicleState {
    public enum Status { OWNED, DEPLOYED, PARKED, RECOVERY, DESTROYED }
    public record Vehicle(UUID id,UUID owner,Status status,int fuel,int health,String world,double x,double y,double z,double yaw) {
        public Vehicle {
            Objects.requireNonNull(id);Objects.requireNonNull(owner);Objects.requireNonNull(status);
            if(fuel<0||fuel>VehicleRules.MAX_FUEL||health<0||health>100||world==null||world.length()>100||
                !world.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Double.isFinite(yaw)||
                Math.abs(x)>30_000_000||Math.abs(z)>30_000_000||y < -2048||y>2048||Math.abs(yaw)>360||
                ((status==Status.DESTROYED)!=(health==0)))throw new IllegalArgumentException("Invalid vehicle record");
        }
        public Vehicle at(Status next,int fuel,int health,String world,double x,double y,double z,double yaw) {
            if(status==Status.DESTROYED)throw new IllegalStateException("Destroyed vehicles cannot return");
            return new Vehicle(id,owner,next,fuel,health,world,x,y,z,yaw);
        }
        public Vehicle status(Status next) {return at(next,fuel,health,world,x,y,z,yaw);}
        public Vehicle damage(int amount) {
            if(status!=Status.DEPLOYED||amount<1||amount>100)throw new IllegalStateException("Invalid damage");
            int left=Math.max(0,health-amount);
            return at(left==0?Status.DESTROYED:status,fuel,left,world,x,y,z,yaw);
        }
    }
    public record Fleet(int schema,long revision,Map<UUID,Vehicle> vehicles) {
        public Fleet {
            if(schema!=1||revision<0||vehicles==null||vehicles.size()>VehicleRules.MAX_RECORDS)throw new IllegalArgumentException("Incompatible fleet");
            vehicles=Map.copyOf(vehicles);
            for(var e:vehicles.entrySet())if(!e.getKey().equals(e.getValue().id()))throw new IllegalArgumentException("Mismatched vehicle ID");
        }
        public static Fleet empty(){return new Fleet(1,0,Map.of());}
        public Fleet put(Vehicle v){var copy=new HashMap<>(vehicles);copy.put(v.id(),v);return new Fleet(1,Math.addExact(revision,1),copy);}
        public Fleet recoverInterrupted(){
            var copy=new HashMap<>(vehicles);
            copy.replaceAll((id,v)->v.status()==Status.DEPLOYED?v.status(Status.RECOVERY):v);
            return new Fleet(1,Math.addExact(revision,1),copy);
        }
        public long owned(UUID owner){return vehicles.values().stream().filter(v->v.owner().equals(owner)&&v.status()!=Status.DESTROYED).count();}
    }
}
