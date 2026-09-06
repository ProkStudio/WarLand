package ru.warland.war;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import ru.warland.core.GameConfig;
import ru.warland.data.Store;
import ru.warland.nations.NationsService;
import ru.warland.war.WarRules.*;
import ru.warland.war.WarService.War;

/** All authority-changing checks run on the serialized SQLite worker, inside the write transaction. */
final class WarRepository {
    record Snapshot(List<War> wars, Map<String, Schedule> schedules) {}
    private static final long DAY = 86_400_000L;
    private final Store db;
    private final GameConfig config;
    private final LongSupplier clock;

    WarRepository(Store db, GameConfig config, LongSupplier clock) {
        this.db = db; this.config = config; this.clock = clock;
    }

    CompletableFuture<Void> initialize() {
        return db.tx(c -> {
            validateConfig();
            Store.update(c, "CREATE TABLE IF NOT EXISTS war_captures(war TEXT NOT NULL,dimension TEXT NOT NULL,x INTEGER NOT NULL,z INTEGER NOT NULL,side TEXT NOT NULL,window TEXT NOT NULL,PRIMARY KEY(war,dimension,x,z))");
            Store.update(c, "CREATE TABLE IF NOT EXISTS war_settings(war TEXT PRIMARY KEY,hour INTEGER NOT NULL,hours INTEGER NOT NULL,immunity_days INTEGER NOT NULL)");
            Store.update(c, "CREATE TABLE IF NOT EXISTS war_peace_offers(war TEXT PRIMARY KEY,offered_by TEXT NOT NULL,expires INTEGER NOT NULL)");
            Store.update(c, "CREATE INDEX IF NOT EXISTS war_capture_window ON war_captures(war,side,window)");
            // Additive adoption for existing wars. A later config edit must not move an announced window.
            Store.update(c, "INSERT OR IGNORE INTO war_settings(war,hour,hours,immunity_days) SELECT id,?,?,? FROM wars",
                    config.warHourMoscow, config.warWindowHours, config.warImmunityDays);
            List<String> due = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT id FROM wars WHERE status='ACTIVE' AND ends<=?")) {
                p.setLong(1, clock.getAsLong());
                try (ResultSet r = p.executeQuery()) { while (r.next()) due.add(r.getString(1)); }
            }
            for (String id : due) settle(c, id, clock.getAsLong());
            return null;
        });
    }

    CompletableFuture<Snapshot> snapshot() {
        return db.submit(c -> {
            List<War> wars = new ArrayList<>(); Map<String, Schedule> schedules = new HashMap<>();
            try (PreparedStatement p = c.prepareStatement("SELECT w.*,s.hour,s.hours FROM wars w JOIN war_settings s ON s.war=w.id WHERE w.status='ACTIVE' OR w.ends+s.immunity_days*86400000>?")) {
                p.setLong(1, clock.getAsLong());
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        War w = read(r); wars.add(w);
                        schedules.put(w.id(), new Schedule(r.getInt("hour"), r.getInt("hours")));
                    }
                }
            }
            return new Snapshot(List.copyOf(wars), Map.copyOf(schedules));
        });
    }

    CompletableFuture<String> declare(UUID player, String target) {
        return db.tx(c -> {
            validateConfig();
            if (!config.enableWarCapture) throw NationsService.rule("Войны ещё не открыты: необходимы боевые испытания");
            long now = clock.getAsLong();
            String nation = NationsService.require(c, player, "war");
            String other = target(c, nation, target);
            if (Store.scalar(c, "SELECT COUNT(*) FROM wars WHERE status='ACTIVE' AND (attacker IN (?,?) OR defender IN (?,?))", nation, other, nation, other) > 0)
                throw NationsService.rule("Одна из сторон уже участвует в войне");
            if (Store.scalar(c, "SELECT COUNT(*) FROM wars w JOIN war_settings s ON s.war=w.id WHERE w.ends+s.immunity_days*86400000>? AND ((attacker=? AND defender=?) OR (attacker=? AND defender=?))", now, nation, other, other, nation) > 0)
                throw NationsService.rule("Действует послевоенный иммунитет");
            requireNoPact(c, nation, other, now);
            long created = Store.scalar(c, "SELECT created FROM nations WHERE id=?", other);
            if (created > now - DAY) throw NationsService.rule("Новое государство защищено первые 24 часа");
            String id = UUID.randomUUID().toString();
            long starts = Math.addExact(now, WarRules.MOBILIZATION_MILLIS);
            long ends = Math.addExact(starts, Math.multiplyExact(config.warHours, 3_600_000L));
            if (!Store.change(c, Store.nation(nation), -config.warCost, id, "war-declare"))
                throw NationsService.rule("В казне недостаточно средств");
            Store.update(c, "INSERT INTO wars(id,attacker,defender,declared,starts,ends,status) VALUES(?,?,?,?,?,?,'ACTIVE')", id, nation, other, now, starts, ends);
            Store.update(c, "INSERT INTO war_settings(war,hour,hours,immunity_days) VALUES(?,?,?,?)", id, config.warHourMoscow, config.warWindowHours, config.warImmunityDays);
            audit(c, player.toString(), "war-declare", id, now);
            return "Война объявлена. Мобилизация 12 часов. Окна: " + new Schedule(config.warHourMoscow, config.warWindowHours).label() + ".";
        });
    }

    CompletableFuture<Void> capture(CaptureKey key, UUID participant) {
        return db.tx(c -> {
            long now = clock.getAsLong();
            if (!config.enableWarCapture) throw NationsService.rule("Захват отключён");
            War w = requireWar(c, key.war());
            String opponent = opponent(w, key.side());
            Schedule schedule = schedule(c, w.id());
            if (!w.status().equals("ACTIVE") || !schedule.open(now, w.starts(), w.ends()) || !schedule.windowKey(now).equals(key.window()))
                throw NationsService.rule("Окно закрыто или изменилось");
            if (!key.side().equals(NationsService.requireMember(c, participant)))
                throw NationsService.rule("Участник больше не представляет эту сторону");
            requireNoPact(c, key.side(), opponent, now);
            if (!"minecraft:overworld".equals(key.dimension())) throw NationsService.rule("Захват доступен только в Верхнем мире");
            Chunk target = new Chunk(key.x(), key.z());
            Chunk attackerCapital = capital(c, key.side(), key.dimension());
            Chunk defenderCapital = capital(c, opponent, key.dimension());
            if (target.equals(defenderCapital)) throw NationsService.rule("Столичный чанк защищён");
            if (!opponent.equals(Store.string(c, "SELECT nation FROM claims WHERE dimension=? AND x=? AND z=?", key.dimension(), key.x(), key.z())))
                throw NationsService.rule("Цель изменилась");
            Set<Chunk> attackerClaims = claims(c, key.side(), key.dimension());
            Set<Chunk> defenderClaims = claims(c, opponent, key.dimension());
            attackerClaims.add(target); defenderClaims.remove(target);
            if (!WarRules.connected(attackerClaims, attackerCapital)) throw NationsService.rule("Цель должна примыкать к связанной территории атакующих");
            if (!WarRules.connected(defenderClaims, defenderCapital)) throw NationsService.rule("Захват не должен разрывать территорию защитников");
            // The old implementation used calendar dates; count both dates conservatively when adopting an overnight window.
            String calendarDay = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Europe/Moscow")).toLocalDate().toString();
            if (Store.scalar(c, "SELECT COUNT(*) FROM war_captures WHERE war=? AND side=? AND window IN (?,?)", w.id(), key.side(), key.window(), calendarDay) >= WarRules.CAPTURES_PER_WINDOW)
                throw NationsService.rule("Лимит захватов за окно");
            if (Store.scalar(c, "SELECT COUNT(*) FROM war_captures WHERE war=? AND dimension=? AND x=? AND z=?", w.id(), key.dimension(), key.x(), key.z()) > 0)
                throw NationsService.rule("Цель уже зачтена");
            if (Store.update(c, "UPDATE claims SET nation=? WHERE dimension=? AND x=? AND z=? AND nation=?", key.side(), key.dimension(), key.x(), key.z(), opponent) != 1)
                throw NationsService.rule("Цель изменилась");
            Store.update(c, "INSERT INTO war_captures(war,dimension,x,z,side,window) VALUES(?,?,?,?,?,?)", w.id(), key.dimension(), key.x(), key.z(), key.side(), key.window());
            Store.update(c, key.side().equals(w.attacker()) ? "UPDATE wars SET attack_score=attack_score+20,last_capture=? WHERE id=?" : "UPDATE wars SET defend_score=defend_score+20,last_capture=? WHERE id=?", now, w.id());
            audit(c, participant.toString(), "war-capture", w.id() + ":" + key.dimension() + ":" + key.x() + ":" + key.z(), now);
            return null;
        });
    }

    CompletableFuture<String> peace(UUID player, String targetName) {
        return db.tx(c -> {
            long now = clock.getAsLong();
            String nation = NationsService.require(c, player, "war");
            War w = activePair(c, nation, target(c, nation, targetName), now);
            String offered = Store.string(c, "SELECT offered_by FROM war_peace_offers WHERE war=? AND expires>?", w.id(), now);
            if (offered != null && !offered.equals(nation) && (offered.equals(w.attacker()) || offered.equals(w.defender()))) {
                finish(c, w, "PEACE", now, player.toString(), "war-peace");
                return "Мирный договор заключён. Территории сохраняются у текущих владельцев; действует послевоенный иммунитет.";
            }
            if (nation.equals(offered)) return "Ваше предложение мира уже ожидает подтверждения другой стороны.";
            Store.update(c, "INSERT INTO war_peace_offers(war,offered_by,expires) VALUES(?,?,?) ON CONFLICT(war) DO UPDATE SET offered_by=excluded.offered_by,expires=excluded.expires", w.id(), nation, now + 600_000L);
            audit(c, player.toString(), "war-peace-offer", w.id(), now);
            return "Предложение мира отправлено на 10 минут. Другая сторона подтверждает той же командой. Захват пока не прекращён.";
        });
    }

    CompletableFuture<String> surrender(UUID player, String targetName) {
        return db.tx(c -> {
            long now = clock.getAsLong();
            String nation = NationsService.require(c, player, "war");
            War w = activePair(c, nation, target(c, nation, targetName), now);
            finish(c, w, nation.equals(w.attacker()) ? "DEFENDER_WIN" : "ATTACKER_WIN", now, player.toString(), "war-surrender");
            return "Капитуляция принята. Дополнительные территории и деньги не изымаются; действует послевоенный иммунитет.";
        });
    }

    CompletableFuture<Void> settle(String warId) { return db.tx(c -> { settle(c, warId, clock.getAsLong()); return null; }); }

    private void settle(Connection c, String warId, long now) throws SQLException {
        War current = requireWar(c, warId);
        if (current.status().equals("ACTIVE") && now >= current.ends())
            finish(c, current, WarRules.result(current.attackScore(), current.defendScore()), current.ends(), "system", "war-settle");
    }

    private static void finish(Connection c, War w, String status, long ended, String actor, String action) throws SQLException {
        if (Store.update(c, "UPDATE wars SET status=?,ends=? WHERE id=? AND status='ACTIVE'", status, ended, w.id()) != 1)
            throw NationsService.rule("Война уже завершена");
        Store.update(c, "DELETE FROM war_peace_offers WHERE war=?", w.id());
        audit(c, actor, action, w.id() + ":" + status, ended);
    }

    private static War activePair(Connection c, String a, String b, long now) throws SQLException {
        String id = Store.string(c, "SELECT id FROM wars WHERE status='ACTIVE' AND ends>? AND ((attacker=? AND defender=?) OR (attacker=? AND defender=?))", now, a, b, b, a);
        if (id == null) throw NationsService.rule("Между государствами нет незавершённой войны");
        return requireWar(c, id);
    }
    private static String target(Connection c, String nation, String target) throws SQLException {
        String other = Store.string(c, "SELECT id FROM nations WHERE name=? COLLATE NOCASE", target);
        if (other == null || nation.equals(other)) throw NationsService.rule("Укажите другое государство");
        return other;
    }
    private static String opponent(War w, String side) throws SQLException {
        if (w.attacker().equals(side)) return w.defender();
        if (w.defender().equals(side)) return w.attacker();
        throw NationsService.rule("Сторона не участвует в войне");
    }
    private static void requireNoPact(Connection c, String a, String b, long now) throws SQLException {
        if (Store.scalar(c, "SELECT COUNT(*) FROM diplomacy WHERE state='PACT' AND expires>? AND ((a=? AND b=?) OR (a=? AND b=?))", now, a, b, b, a) > 0)
            throw NationsService.rule("Действует пакт о ненападении");
    }
    private static Chunk capital(Connection c, String nation, String dimension) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT cx,cz FROM nations WHERE id=? AND dimension=?")) {
            Store.bind(p, nation, dimension);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw NationsService.rule("Государство или измерение столицы изменилось");
                return new Chunk(r.getInt(1) >> 4, r.getInt(2) >> 4);
            }
        }
    }
    private static Set<Chunk> claims(Connection c, String nation, String dimension) throws SQLException {
        Set<Chunk> out = new HashSet<>();
        try (PreparedStatement p = c.prepareStatement("SELECT x,z FROM claims WHERE nation=? AND dimension=? LIMIT 1025")) {
            Store.bind(p, nation, dimension);
            try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(new Chunk(r.getInt(1), r.getInt(2))); }
        }
        if (out.size() > 1024) throw NationsService.rule("Территория требует проверки администрацией");
        return out;
    }
    private static War requireWar(Connection c, String id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM wars WHERE id=?")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) { if (r.next()) return read(r); }
        }
        throw NationsService.rule("Война не найдена");
    }
    private static War read(ResultSet r) throws SQLException {
        return new War(r.getString("id"), r.getString("attacker"), r.getString("defender"), r.getLong("declared"), r.getLong("starts"), r.getLong("ends"), r.getString("status"), r.getInt("attack_score"), r.getInt("defend_score"));
    }
    private static Schedule schedule(Connection c, String war) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT hour,hours FROM war_settings WHERE war=?")) {
            p.setString(1, war);
            try (ResultSet r = p.executeQuery()) { if (r.next()) return new Schedule(r.getInt(1), r.getInt(2)); }
        }
        throw NationsService.rule("Расписание войны отсутствует");
    }
    private static void audit(Connection c, String actor, String action, String target, long now) throws SQLException {
        Store.update(c, "INSERT INTO audit(actor,action,target,created) VALUES(?,?,?,?)", actor, action, target, now);
    }
    private void validateConfig() throws SQLException {
        if (config.mobilizationHours != 12 || config.warHours < 24 || config.warHours > 168 || config.warImmunityDays < 5 || config.warImmunityDays > 7 || config.warCost < 1 || config.warCost > 1_000_000_000_000L)
            throw NationsService.rule("Небезопасная конфигурация войны: мобилизация 12 часов, иммунитет 5–7 дней");
        new Schedule(config.warHourMoscow, config.warWindowHours);
    }
}
