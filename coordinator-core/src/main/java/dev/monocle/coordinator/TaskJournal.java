package dev.monocle.coordinator;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;

/** Durable task intents: changed rows only, with shared workflow packages stored once. */
public final class TaskJournal implements AutoCloseable {
    private record Value(JsonElement snapshot,String text,int bytes) { }
    private record Package(Value value,String hash) { }
    private record Row(Value value,String hash,int ordinal) { }
    private final Path file,legacy,marker;
    private final String collection;
    private Connection connection;
    private Object fileKey;
    private int dataVersion;
    private boolean loaded,initialized;
    private final Map<String,Row> rows=new LinkedHashMap<>();
    private final Map<String,Value> metadata=new LinkedHashMap<>();
    private final Map<String,Package> packages=new HashMap<>();
    private final IdentityHashMap<JsonObject,Package> packageInstances=new IdentityHashMap<>();

    public TaskJournal(Path file,Path legacy,String collection){
        this.file=file.toAbsolutePath().normalize();this.legacy=legacy;
        marker=this.file.resolveSibling(this.file.getFileName()+".initialized");
        if(!file.getFileName().toString().endsWith(".sqlite")||!Set.of("runs","tasks").contains(collection))throw new IllegalArgumentException("Invalid task journal");
        this.collection=collection;
    }
    public synchronized boolean requiresImport(){return loaded&&!initialized&&Files.exists(legacy);}
    private Connection open()throws SQLException,IOException{
        if(connection!=null)return connection;
        Files.createDirectories(file.getParent());
        Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(var s=c.createStatement()){
            s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");
            try(var r=s.executeQuery("PRAGMA user_version")){r.next();int v=r.getInt(1);if(v!=0&&v!=1)throw new SQLException("Unsupported task journal version "+v);}
            fileKey=Files.readAttributes(file,BasicFileAttributes.class).fileKey();
            try(var r=s.executeQuery("PRAGMA data_version")){r.next();dataVersion=r.getInt(1);}
        }catch(SQLException|IOException|RuntimeException e){try{c.close();}catch(SQLException close){e.addSuppressed(close);}throw e;}
        connection=c;return c;
    }
    private void verifyFile()throws SQLException,IOException{
        if(!Files.exists(file)||!Objects.equals(fileKey,Files.readAttributes(file,BasicFileAttributes.class).fileKey()))
            throw new SQLException("Task journal was deleted or replaced; inspect recovery data before continuing");
        try(var s=connection.createStatement();var r=s.executeQuery("PRAGMA data_version")){
            r.next();if(r.getInt(1)!=dataVersion)throw new SQLException("Task journal changed outside this runtime; reload before continuing");
        }
    }
    private static Value value(JsonElement json,int limit){
        byte[] bytes=TaskFiles.jsonValueBytes(json,limit);
        return new Value(json.deepCopy(),new String(bytes,StandardCharsets.UTF_8),bytes.length);
    }
    private static Value parsed(String text,int limit){
        if(text==null||text.length()>limit||text.getBytes(StandardCharsets.UTF_8).length>limit)throw new IllegalArgumentException("Task journal value exceeds its limit");
        return new Value(JsonParser.parseString(text),text,text.getBytes(StandardCharsets.UTF_8).length);
    }
    public synchronized JsonObject read(){
        if(loaded&&connection!=null){try{verifyFile();}catch(SQLException|IOException e){throw failure(e);}}
        rows.clear();metadata.clear();packages.clear();packageInstances.clear();initialized=false;loaded=false;
        try{
            if(Files.exists(file)){
                Connection c=open();
                try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();initialized=r.getInt(1)==1;}
                if(initialized){
                    JsonObject root=new JsonObject(),records=new JsonObject();
                    c.setAutoCommit(false);
                    try{
                        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*),COALESCE(SUM(length(CAST(r.value AS BLOB))+COALESCE(length(CAST(p.value AS BLOB)),0)+length(r.id)+16),0)+(SELECT COALESCE(SUM(length(CAST(value AS BLOB))+length(key)+8),0) FROM metadata) FROM records r LEFT JOIN packages p ON p.hash=r.package_hash")){
                            r.next();if(r.getInt(1)>64||r.getLong(2)>TaskFiles.MAX_JOURNAL)throw new IllegalArgumentException("Task journal records exceed their limit");
                        }
                        try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*),COALESCE(SUM(length(CAST(value AS BLOB))),0) FROM packages")){
                            r.next();if(r.getInt(1)>64||r.getLong(2)>TaskFiles.MAX_JOURNAL)throw new IllegalArgumentException("Task journal packages exceed their limit");
                        }
                        boolean collectionFound=false;
                        try(var s=c.createStatement();var r=s.executeQuery("SELECT key,value FROM metadata")){
                            while(r.next()){
                                String key=r.getString(1);Value v=parsed(r.getString(2),TaskFiles.MAX_JOURNAL);
                                if(key.equals("$collection")){if(!v.snapshot.isJsonPrimitive()||!collection.equals(v.snapshot.getAsString()))throw new IllegalArgumentException("Wrong task journal collection");collectionFound=true;}
                                else{metadata.put(key,v);root.add(key,v.snapshot.deepCopy());}
                            }
                        }
                        if(!root.has("version")||!collectionFound)throw new IllegalArgumentException("Incomplete task journal metadata");
                        try(var s=c.createStatement();var r=s.executeQuery("SELECT hash,value FROM packages")){
                            while(r.next()){
                                String hash=r.getString(1);Value v=parsed(r.getString(2),TaskFiles.MAX_PACKAGE);
                                if(!v.snapshot.isJsonObject()||!hash.equals(TaskFiles.hash(v.text)))throw new IllegalArgumentException("Invalid task journal package");
                                packages.put(hash,new Package(v,hash));
                            }
                        }
                        try(var s=c.createStatement();var r=s.executeQuery("SELECT id,value,package_hash,ordinal FROM records ORDER BY ordinal")){
                            while(r.next()){
                                String id=r.getString(1);UUID.fromString(id);Value v=parsed(r.getString(2),TaskFiles.MAX_JOURNAL);String hash=r.getString(3);
                                JsonObject row=v.snapshot.getAsJsonObject().deepCopy();if(row.has("package"))throw new IllegalArgumentException("Inline task journal package");
                                if(hash!=null){Package p=packages.get(hash);if(p==null)throw new IllegalArgumentException("Missing task journal package");JsonObject copy=p.value.snapshot.getAsJsonObject().deepCopy();row.add("package",copy);packageInstances.put(copy,p);}
                                rows.put(id,new Row(v,hash,r.getInt(4)));records.add(id,row);
                            }
                        }
                        root.add(collection,records);checkRoot(root);c.commit();loaded=true;return root;
                    }catch(SQLException|RuntimeException e){c.rollback();throw e;}
                    finally{c.setAutoCommit(true);}
                }
            }
            if(Files.exists(marker))throw new IllegalStateException("Previously initialized task database is missing or incomplete; restore it instead of replaying legacy JSON");
            JsonObject root=TaskFiles.read(legacy);if(!root.isEmpty())checkRoot(root);loaded=true;return root;
        }catch(SQLException|IOException e){throw failure(e);}
    }
    private void checkRoot(JsonObject root){
        StashCatalog.integer(root,"version",1,1);
        if(!root.has(collection)||!root.get(collection).isJsonObject()||root.getAsJsonObject(collection).size()>64||root.has("$collection"))throw new IllegalArgumentException("Invalid task journal records");
    }
    public synchronized void write(JsonObject root){
        if(!loaded)read();checkRoot(root);
        Map<String,Row> nextRows=new LinkedHashMap<>();Map<String,Value> nextMeta=new LinkedHashMap<>();
        Map<String,Package> nextPackages=new HashMap<>();IdentityHashMap<JsonObject,Package> instances=new IdentityHashMap<>();
        long size=32;int ordinal=0;
        for(var e:root.getAsJsonObject(collection).entrySet()){
            UUID.fromString(e.getKey());JsonObject record=e.getValue().getAsJsonObject(),data=new JsonObject();String hash=null;
            if(record.has("package")){
                JsonObject input=record.getAsJsonObject("package");Package p=packageInstances.get(input);
                if(p==null||!input.equals(p.value.snapshot)){Value v=value(input,TaskFiles.MAX_PACKAGE);p=new Package(v,TaskFiles.hash(v.text));}
                hash=p.hash;instances.put(input,p);nextPackages.put(hash,p);size+=p.value.bytes;
            }
            for(var field:record.entrySet())if(!field.getKey().equals("package"))data.add(field.getKey(),field.getValue());
            Row old=rows.get(e.getKey());Value v=old!=null&&data.equals(old.value.snapshot)?old.value:value(data,TaskFiles.MAX_JOURNAL);
            nextRows.put(e.getKey(),new Row(v,hash,ordinal++));size+=v.bytes+e.getKey().length()+16;
        }
        for(var e:root.entrySet())if(!e.getKey().equals(collection)){
            Value old=metadata.get(e.getKey());Value v=old!=null&&e.getValue().equals(old.snapshot)?old:value(e.getValue(),TaskFiles.MAX_JOURNAL);
            nextMeta.put(e.getKey(),v);size+=v.bytes+e.getKey().length()+8;
        }
        if(size>TaskFiles.MAX_JOURNAL)throw new IllegalArgumentException("Task journal exceeds its size limit");
        try{
            Connection c=open();verifyFile();
            if(initialized&&nextRows.equals(rows)&&nextMeta.equals(metadata)){markInitialized();return;}
            c.setAutoCommit(false);
            try{
                if(!initialized)try(var s=c.createStatement()){
                    s.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL CHECK(json_valid(value)))");
                    s.execute("CREATE TABLE packages(hash TEXT PRIMARY KEY,value TEXT NOT NULL CHECK(json_valid(value)))");
                    s.execute("CREATE TABLE records(id TEXT PRIMARY KEY,value TEXT NOT NULL CHECK(json_valid(value)),package_hash TEXT REFERENCES packages(hash),ordinal INTEGER NOT NULL)");
                    try(var insert=c.prepareStatement("INSERT INTO metadata(key,value) VALUES ('$collection',?)")){insert.setString(1,new JsonPrimitive(collection).toString());insert.executeUpdate();}
                    s.execute("PRAGMA user_version=1");
                }
                try(var insert=c.prepareStatement("INSERT INTO packages(hash,value) VALUES (?,?) ON CONFLICT(hash) DO NOTHING")){
                    for(var p:nextPackages.values())if(!packages.containsKey(p.hash)){insert.setString(1,p.hash);insert.setString(2,p.value.text);insert.addBatch();}insert.executeBatch();
                }
                try(var set=c.prepareStatement("INSERT INTO records(id,value,package_hash,ordinal) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value,package_hash=excluded.package_hash,ordinal=excluded.ordinal");var delete=c.prepareStatement("DELETE FROM records WHERE id=?")){
                    for(var e:nextRows.entrySet())if(!e.getValue().equals(rows.get(e.getKey()))){var row=e.getValue();set.setString(1,e.getKey());set.setString(2,row.value.text);set.setString(3,row.hash);set.setInt(4,row.ordinal);set.addBatch();}
                    for(String key:rows.keySet())if(!nextRows.containsKey(key)){delete.setString(1,key);delete.addBatch();}set.executeBatch();delete.executeBatch();
                }
                try(var set=c.prepareStatement("INSERT INTO metadata(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value");var delete=c.prepareStatement("DELETE FROM metadata WHERE key=?")){
                    for(var e:nextMeta.entrySet())if(!e.getValue().equals(metadata.get(e.getKey()))){set.setString(1,e.getKey());set.setString(2,e.getValue().text);set.addBatch();}
                    for(String key:metadata.keySet())if(!nextMeta.containsKey(key)){delete.setString(1,key);delete.addBatch();}set.executeBatch();delete.executeBatch();
                }
                try(var s=c.createStatement()){s.executeUpdate("DELETE FROM packages WHERE hash NOT IN (SELECT package_hash FROM records WHERE package_hash IS NOT NULL)");}
                c.commit();verifyFile();initialized=true;
                rows.clear();rows.putAll(nextRows);metadata.clear();metadata.putAll(nextMeta);packages.clear();packages.putAll(nextPackages);packageInstances.clear();packageInstances.putAll(instances);
                markInitialized();
            }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw e;}
            finally{c.setAutoCommit(true);}
        }catch(SQLException|IOException e){throw failure(e);}
    }
    private void markInitialized(){
        if(Files.exists(marker))return;
        // Never fall back to a stale import after a later database deletion.
        JsonObject value=new JsonObject();value.addProperty("version",1);value.addProperty("collection",collection);TaskFiles.write(marker,value);
    }
    private IllegalStateException failure(Exception e){return new IllegalStateException("Task checkpoint database failed; no new action acknowledged: "+file,e);}
    @Override public synchronized void close(){
        if(connection!=null)try{connection.close();}catch(SQLException e){throw failure(e);}finally{connection=null;loaded=false;}
    }
}
