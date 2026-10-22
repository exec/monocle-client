package dev.monocle.coordinator;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.BiFunction;

/** Per-stash SQLite stores. Legacy JSON snapshots and delta journals are deliberately not imported. */
public final class StashStore {
    private static final Map<Path,Connection> writers=new LinkedHashMap<>();
    private static final Map<Path,Long> generations=new HashMap<>();
    private static long generation;
    private StashStore() { }
    private static Path path(Path file){
        Path result=file.toAbsolutePath().normalize();
        if(!result.getFileName().toString().endsWith(".sqlite"))throw new IllegalArgumentException("Stash storage requires a .sqlite database; redefine legacy stashes");
        return result;
    }
    private static Connection open(Path file)throws SQLException,IOException{
        Connection saved=writers.get(file);if(saved!=null)return saved;
        Files.createDirectories(file.getParent());Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(var s=c.createStatement()){
            s.execute("PRAGMA busy_timeout=5000");
            int version;try(var r=s.executeQuery("PRAGMA user_version")){r.next();version=r.getInt(1);}
            if(version!=0&&version!=1)throw new SQLException("Unsupported stash database version "+version);
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");
            if(version==0){
                c.setAutoCommit(false);
                s.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL CHECK(json_valid(value)))");
                s.execute("CREATE TABLE containers (key TEXT PRIMARY KEY, x INTEGER, y INTEGER, z INTEGER, status TEXT NOT NULL, observed_at INTEGER NOT NULL, value TEXT NOT NULL CHECK(json_valid(value)))");
                s.execute("CREATE INDEX containers_position ON containers(x,z,y)");
                s.execute("CREATE INDEX containers_freshness ON containers(status,observed_at)");
                s.execute("CREATE TABLE scan_checkpoints (binding TEXT PRIMARY KEY, plan TEXT NOT NULL, value TEXT NOT NULL CHECK(json_valid(value)))");s.execute("PRAGMA user_version=1");
                c.commit();c.setAutoCommit(true);
            }else{
                try(var r=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('metadata','containers','scan_checkpoints')")){r.next();if(r.getInt(1)!=3)throw new SQLException("Incomplete stash schema; restore the database from backup");}
            }
        }catch(Throwable e){try{c.close();}catch(SQLException close){e.addSuppressed(close);}throw e;}
        writers.put(file,c);
        generations.put(file,++generation);
        if(writers.size()>32){var first=writers.entrySet().iterator();var old=first.next();old.getValue().close();generations.remove(old.getKey());first.remove();}
        return c;
    }
    private interface Work<T>{T run(Connection c)throws SQLException;}
    private static <T>T transaction(Path file,Work<T> work){
        try{
            Connection c=open(path(file));c.setAutoCommit(false);
            try{T result=work.run(c);c.commit();generations.put(path(file),++generation);return result;}
            catch(SQLException|RuntimeException|Error e){try{c.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);writers.remove(path(file));try{c.close();}catch(SQLException close){e.addSuppressed(close);}}throw e;}
            finally{if(!c.isClosed())c.setAutoCommit(true);}
        }catch(SQLException|IOException e){throw new IllegalStateException("Stash database operation failed; transaction not acknowledged: "+file,e);}
    }
    private static JsonObject load(Connection c,Set<String> keys)throws SQLException{
        JsonObject result=new JsonObject();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT key,value FROM metadata ORDER BY key")){while(r.next())result.add(r.getString(1),JsonParser.parseString(r.getString(2)));}
        if(result.isEmpty())return result;
        JsonObject rows=new JsonObject();result.add("containers",rows);
        if(keys==null){try(var s=c.createStatement();var r=s.executeQuery("SELECT key,value FROM containers ORDER BY key")){while(r.next())rows.add(r.getString(1),JsonParser.parseString(r.getString(2)));}}
        else try(var s=c.prepareStatement("SELECT value FROM containers WHERE key=?")){for(String key:keys){s.setString(1,key);try(var r=s.executeQuery()){if(r.next())rows.add(key,JsonParser.parseString(r.getString(1)));}}}
        return result;
    }
    private static <T>T readOnly(Path file,Work<T> work){
        Path checked=path(file);
        try{
            synchronized(StashStore.class){open(checked);}
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+checked)){
                try(var s=c.createStatement()){s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA query_only=ON");}
                c.setAutoCommit(false);
                T result=work.run(c);c.commit();return result;
            }
        }catch(SQLException|IOException e){throw new IllegalStateException("Could not read stash database: "+file,e);}
    }
    public static JsonObject read(Path file){
        if(!Files.exists(path(file)))return new JsonObject();
        return readOnly(file,c->load(c,null));
    }
    /** Own commits plus SQLite's external-commit counter; reopening always changes the token. */
    static synchronized String revision(Path file){
        Path checked=path(file);if(!Files.exists(checked))return "missing";
        try(var s=open(checked).createStatement();var r=s.executeQuery("PRAGMA data_version")){
            r.next();return generations.get(checked)+":"+r.getLong(1);
        }catch(SQLException|IOException e){throw new IllegalStateException("Could not read stash revision: "+file,e);}
    }
    static JsonObject readRows(Path file,Set<String> keys){
        if(!Files.exists(path(file)))return new JsonObject();
        return readOnly(file,c->load(c,keys));
    }
    private static Map<String,String> metadata(JsonObject db){
        Map<String,String> result=new LinkedHashMap<>();
        for(var e:db.entrySet())if(!e.getKey().equals("containers")){
            JsonObject bounded=new JsonObject();bounded.add(e.getKey(),e.getValue());TaskFiles.jsonBytes(bounded,TaskFiles.MAX_JOURNAL);result.put(e.getKey(),e.getValue().toString());
        }
        return result;
    }
    private static void persist(Connection c,JsonObject db,Set<String> changed)throws SQLException{
        Map<String,String> meta=metadata(db),prior=new HashMap<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT key,value FROM metadata")){while(r.next())prior.put(r.getString(1),r.getString(2));}
        try(var set=c.prepareStatement("INSERT INTO metadata(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value");var delete=c.prepareStatement("DELETE FROM metadata WHERE key=?")){
            for(var e:meta.entrySet())if(!e.getValue().equals(prior.remove(e.getKey()))){set.setString(1,e.getKey());set.setString(2,e.getValue());set.addBatch();}
            for(String key:prior.keySet()){delete.setString(1,key);delete.addBatch();}set.executeBatch();delete.executeBatch();
        }
        JsonObject rows=db.getAsJsonObject("containers");
        try(var set=c.prepareStatement("INSERT INTO containers(key,x,y,z,status,observed_at,value) VALUES (?,?,?,?,?,?,?) ON CONFLICT(key) DO UPDATE SET x=excluded.x,y=excluded.y,z=excluded.z,status=excluded.status,observed_at=excluded.observed_at,value=excluded.value");var delete=c.prepareStatement("DELETE FROM containers WHERE key=?")){
            for(String key:changed){
                if(!rows.has(key)){delete.setString(1,key);delete.addBatch();continue;}
                JsonObject row=rows.getAsJsonObject(key);TaskFiles.jsonBytes(row,StashCatalog.MAX_OBSERVATION_BYTES);
                set.setString(1,key);int i=2;for(String axis:List.of("x","y","z")){if(row.has(axis))set.setInt(i,row.get(axis).getAsInt());else set.setNull(i,Types.INTEGER);i++;}
                set.setString(5,TaskWire.text(row,"status"));set.setLong(6,row.has("observedAt")?row.get("observedAt").getAsLong():0);set.setString(7,row.toString());set.addBatch();
            }set.executeBatch();delete.executeBatch();
        }
    }
    /** Observation ingestion loads and commits only the affected rows, never the entire container map. */
    static synchronized JsonObject update(Path file,Set<String> keys,BiFunction<JsonObject,Integer,Set<String>> edit,JsonObject checkpoint){
        return transaction(file,c->{
            int count;try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM containers")){r.next();count=r.getInt(1);}
            Set<String> selected=new HashSet<>(keys);
            if(count>=StashCatalog.MAX_CONTAINERS)try(var s=c.createStatement();var r=s.executeQuery("SELECT key FROM containers WHERE status='removed' ORDER BY observed_at,key LIMIT 1")){if(r.next())selected.add(r.getString(1));}
            JsonObject db=load(c,selected);Set<String> changed=edit.apply(db,count);persist(c,db,changed);
            if(checkpoint!=null)try(var s=c.prepareStatement("INSERT INTO scan_checkpoints(binding,plan,value) VALUES (?,?,?) ON CONFLICT(binding) DO UPDATE SET plan=excluded.plan,value=excluded.value")){
                TaskFiles.jsonBytes(checkpoint,TaskFiles.MAX_JOURNAL);s.setString(1,TaskWire.text(checkpoint,"binding"));s.setString(2,TaskWire.text(checkpoint,"plan"));s.setString(3,checkpoint.toString());s.executeUpdate();
            }
            return db;
        });
    }
    public static JsonObject checkpoint(Path file,String binding){
        if(!Files.exists(path(file)))return new JsonObject();
        return readOnly(file,c->{try(var s=c.prepareStatement("SELECT value FROM scan_checkpoints WHERE binding=?")){s.setString(1,binding);try(var r=s.executeQuery()){return r.next()?JsonParser.parseString(r.getString(1)).getAsJsonObject():new JsonObject();}}});
    }
    public static synchronized void record(Path file,JsonObject db,Set<String> changed){transaction(file,c->{persist(c,db,changed);return null;});}
    public static synchronized void write(Path file,JsonObject db){
        transaction(file,c->{try(var s=c.createStatement()){s.executeUpdate("DELETE FROM containers");}persist(c,db,db.getAsJsonObject("containers").keySet());return null;});
    }
    static synchronized void clearCache(){
        for(Connection c:writers.values())try{c.close();}catch(SQLException e){throw new IllegalStateException("Could not close stash database",e);}writers.clear();generations.clear();
    }
    public static synchronized void close(Path root){
        Path prefix=root.toAbsolutePath().normalize();
        for(var iterator=writers.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!entry.getKey().startsWith(prefix))continue;try{entry.getValue().close();}catch(SQLException e){throw new IllegalStateException("Could not close stash database",e);}generations.remove(entry.getKey());iterator.remove();}
    }
}
