package ru.warland.data;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** One database worker, short transactions, no JDBC on Minecraft's tick thread. */
public final class Store implements AutoCloseable {
    @FunctionalInterface public interface Work<T> { T run(Connection c) throws Exception; }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r,"warland-sqlite"));
    private final Path file;
    private Connection connection;
    public Store(Path file) { this.file=file; }
    public CompletableFuture<Void> start() { return submit(c -> {
        Files.createDirectories(file.getParent());
        Class.forName("org.sqlite.JDBC");
        connection=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());
        try (Statement s=connection.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL"); s.execute("PRAGMA busy_timeout=5000");
            try (ResultSet r=s.executeQuery("PRAGMA user_version")) { if(r.next() && r.getInt(1)>1) throw new SQLException("Unsupported database version"); }
            for(String ddl:SCHEMA) s.execute(ddl);
            s.execute("PRAGMA user_version=1");
            s.executeUpdate("UPDATE inventory_journal SET status='ABORTED' WHERE status='PREPARED'");
        }
        return null;
    }); }
    public <T> CompletableFuture<T> submit(Work<T> work) {
        CompletableFuture<T> out=new CompletableFuture<>();
        try { worker.execute(() -> { try {out.complete(work.run(connection));} catch(Throwable e){out.completeExceptionally(e);} }); }
        catch(RejectedExecutionException e){out.completeExceptionally(e);} return out;
    }
    public <T> CompletableFuture<T> tx(Work<T> work) { return submit(c -> {
        if(c==null) throw new SQLException("Database not ready");
        c.setAutoCommit(false);
        try { T v=work.run(c); c.commit(); return v; }
        catch(Exception|Error e){c.rollback();throw e;} finally {c.setAutoCommit(true);}
    }); }
    public static int update(Connection c,String sql,Object... params) throws SQLException {
        try(PreparedStatement p=c.prepareStatement(sql)){bind(p,params);return p.executeUpdate();}
    }
    public static void bind(PreparedStatement p,Object... params) throws SQLException { for(int i=0;i<params.length;i++) p.setObject(i+1,params[i]); }
    public static long scalar(Connection c,String sql,Object... params)throws SQLException{
        try(PreparedStatement p=c.prepareStatement(sql)){bind(p,params);try(ResultSet r=p.executeQuery()){return r.next()?r.getLong(1):0;}}
    }
    public static String string(Connection c,String sql,Object... params)throws SQLException{
        try(PreparedStatement p=c.prepareStatement(sql)){bind(p,params);try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    public static String player(UUID id){return "player:"+id;}
    public static String nation(String id){return "nation:"+id;}
    public static void account(Connection c,String owner)throws SQLException {update(c,"INSERT OR IGNORE INTO accounts(owner,balance) VALUES(?,0)",owner);}
    /** Idempotency is scoped to owner AND operation. Conflicting reuse is rejected. */
    public static boolean change(Connection c,String owner,long delta,String operation,String reason)throws SQLException {
        if(operation==null||operation.isBlank()||operation.length()>180||delta < -1_000_000_000_000L||delta > 1_000_000_000_000L) throw new SQLException("Invalid ledger operation");
        try(PreparedStatement p=c.prepareStatement("SELECT delta FROM ledger WHERE owner=? AND operation=?")){
            bind(p,owner,operation);try(ResultSet r=p.executeQuery()){if(r.next()){if(r.getLong(1)!=delta)throw new SQLException("Idempotency conflict");return true;}}
        }
        account(c,owner);
        int n=update(c,"UPDATE accounts SET balance=balance+? WHERE owner=? AND balance+? BETWEEN 0 AND 1000000000000",delta,owner,delta);
        if(n==0)return false;
        update(c,"INSERT INTO ledger(owner,delta,operation,reason,created) VALUES(?,?,?,?,?)",owner,delta,operation,reason,System.currentTimeMillis()); return true;
    }
    public CompletableFuture<Long> balance(UUID id){return submit(c -> scalar(c,"SELECT balance FROM accounts WHERE owner=?",player(id)));}
    public CompletableFuture<Boolean> money(UUID id,long delta,String op,String reason){return tx(c -> change(c,player(id),delta,op,reason));}
    public CompletableFuture<String> state(String ns,String key){return submit(c -> string(c,"SELECT json FROM state WHERE namespace=? AND key=?",ns,key));}
    public CompletableFuture<Void> state(String ns,String key,String json){return tx(c -> {update(c,"INSERT INTO state(namespace,key,json) VALUES(?,?,?) ON CONFLICT(namespace,key) DO UPDATE SET json=excluded.json",ns,key,json);return null;});}
    public CompletableFuture<Void> audit(String actor,String action,String target){return tx(c ->{update(c,"INSERT INTO audit(actor,action,target,created) VALUES(?,?,?,?)",actor,action,target,System.currentTimeMillis());return null;});}
    public CompletableFuture<Void> checkpoint(){return submit(c ->{try(Statement s=c.createStatement()){s.execute("PRAGMA wal_checkpoint(PASSIVE)");}return null;});}
    @Override public void close(){
        try{submit(c->{if(c!=null){try(Statement s=c.createStatement()){s.execute("PRAGMA wal_checkpoint(TRUNCATE)");}c.close();}return null;}).get(20,TimeUnit.SECONDS);}
        catch(Exception e){throw new IllegalStateException("Database shutdown failed",e);}finally{worker.shutdown();}
    }
    private static final String[] SCHEMA={
        "CREATE TABLE IF NOT EXISTS accounts(owner TEXT PRIMARY KEY,balance INTEGER NOT NULL CHECK(balance BETWEEN 0 AND 1000000000000))",
        "CREATE TABLE IF NOT EXISTS ledger(id INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,delta INTEGER NOT NULL,operation TEXT NOT NULL,reason TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(owner,operation))",
        "CREATE TRIGGER IF NOT EXISTS immutable_ledger_update BEFORE UPDATE ON ledger BEGIN SELECT RAISE(ABORT,'immutable ledger'); END",
        "CREATE TRIGGER IF NOT EXISTS immutable_ledger_delete BEFORE DELETE ON ledger BEGIN SELECT RAISE(ABORT,'immutable ledger'); END",
        "CREATE TABLE IF NOT EXISTS state(namespace TEXT NOT NULL,key TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(namespace,key))",
        "CREATE TABLE IF NOT EXISTS profiles(uuid TEXT PRIMARY KEY,name TEXT NOT NULL,joined INTEGER NOT NULL,tutorial INTEGER NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL,active_seconds INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS nations(id TEXT PRIMARY KEY,name TEXT NOT NULL COLLATE NOCASE UNIQUE,owner TEXT NOT NULL,cx INTEGER NOT NULL,cy INTEGER NOT NULL,cz INTEGER NOT NULL,dimension TEXT NOT NULL,level INTEGER NOT NULL DEFAULT 0,tax INTEGER NOT NULL DEFAULT 0,pending_tax INTEGER,effective_tax INTEGER,created INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS members(uuid TEXT PRIMARY KEY,nation TEXT NOT NULL REFERENCES nations(id),rank TEXT NOT NULL,joined INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS claims(dimension TEXT NOT NULL,x INTEGER NOT NULL,z INTEGER NOT NULL,nation TEXT NOT NULL REFERENCES nations(id),created INTEGER NOT NULL,PRIMARY KEY(dimension,x,z))",
        "CREATE INDEX IF NOT EXISTS claims_nation ON claims(nation)",
        "CREATE TABLE IF NOT EXISTS invites(nation TEXT NOT NULL,uuid TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(nation,uuid))",
        "CREATE TABLE IF NOT EXISTS diplomacy(a TEXT NOT NULL,b TEXT NOT NULL,state TEXT NOT NULL,offered_by TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(a,b))",
        "CREATE TABLE IF NOT EXISTS wars(id TEXT PRIMARY KEY,attacker TEXT NOT NULL,defender TEXT NOT NULL,declared INTEGER NOT NULL,starts INTEGER NOT NULL,ends INTEGER NOT NULL,status TEXT NOT NULL,attack_score INTEGER NOT NULL DEFAULT 0,defend_score INTEGER NOT NULL DEFAULT 0,last_capture INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS sale_volume(uuid TEXT NOT NULL,item TEXT NOT NULL,day TEXT NOT NULL,quantity INTEGER NOT NULL,PRIMARY KEY(uuid,item,day))",
        "CREATE TABLE IF NOT EXISTS listings(id INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,stack TEXT NOT NULL,price INTEGER NOT NULL CHECK(price>0),status TEXT NOT NULL,expires INTEGER NOT NULL,created INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS listing_status ON listings(status,expires)",
        "CREATE TABLE IF NOT EXISTS mailbox(id INTEGER PRIMARY KEY AUTOINCREMENT,owner TEXT NOT NULL,stack TEXT NOT NULL,source TEXT NOT NULL UNIQUE,claimed INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS inventory_journal(id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL,before_json TEXT NOT NULL,after_json TEXT NOT NULL,status TEXT NOT NULL,operation TEXT NOT NULL UNIQUE,created INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS inventory_journal_owner ON inventory_journal(uuid,id)",
        "CREATE TABLE IF NOT EXISTS reports(id INTEGER PRIMARY KEY AUTOINCREMENT,author TEXT NOT NULL,target TEXT NOT NULL,body TEXT NOT NULL,location TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'OPEN',created INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,actor TEXT NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL,created INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS sanctions(uuid TEXT PRIMARY KEY,kind TEXT NOT NULL,reason TEXT NOT NULL,expires INTEGER NOT NULL,actor TEXT NOT NULL)"
    };
}
