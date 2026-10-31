package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Synthetic execution state only: migration, changed-row commits and fail-closed recovery. */
final class TaskJournalCheck {
    private static String id(int n){return "00000000-0000-0000-0000-"+String.format("%012d",n);}
    private static JsonObject root(String collection){
        JsonObject root=new JsonObject(),rows=new JsonObject();root.addProperty("version",1);root.add(collection,rows);
        JsonObject packet=JsonParser.parseString("{version:1,entry:'main',programs:{main:{name:'Synthetic wait',script:'return function(ctx) return bot.wait(1) end'}},profiles:{Current:{}},highways:{}}").getAsJsonObject();
        packet.addProperty("syntheticPayload","independently invented test data ".repeat(3000));
        for(int i=1;i<=64;i++){
            JsonObject row=new JsonObject();row.addProperty("status",i==1?"Running":"Complete");row.add("package",packet.deepCopy());
            row.add("state",JsonParser.parseString("{phase:'store',buffered:54,confirmed:12,pending:true,token:'synthetic-receipt'}"));rows.add(id(i),row);
        }
        root.add("original",JsonParser.parseString("{modules:{'auto-eat':{active:false}}}"));return root;
    }
    private static Connection sql(Path file)throws Exception{return DriverManager.getConnection("jdbc:sqlite:"+file);}
    private static int number(Path file,String query)throws Exception{try(var c=sql(file);var s=c.createStatement();var r=s.executeQuery(query)){r.next();return r.getInt(1);}}
    private static void execute(Path file,String query)throws Exception{try(var c=sql(file);var s=c.createStatement()){s.execute(query);}}
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("Invalid checkpoint accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    static void run()throws Exception{
        for(String collection:List.of("runs","tasks"))roundTrip(collection);
        rollback();missingDatabase();malformed();benchmark();
        System.out.println("Task SQLite journal checks passed: legacy import, shared packages, row-only updates, rollback and missing/replaced database guards.");
    }
    private static void roundTrip(String collection)throws Exception{
        Path dir=Files.createTempDirectory("task-journal-check"),db=dir.resolve("tasks.sqlite"),legacy=dir.resolve("tasks.json");JsonObject input=root(collection);TaskFiles.write(legacy,input);byte[] original=Files.readAllBytes(legacy);
        try(var journal=new TaskJournal(db,legacy,collection)){
            JsonObject loaded=journal.read();assert loaded.equals(input)&&journal.requiresImport()&&!Files.exists(db);
            journal.write(loaded);assert !journal.requiresImport();
        }
        assert Arrays.equals(original,Files.readAllBytes(legacy)) : "Migration keeps the legacy recovery snapshot intact";
        assert number(db,"SELECT COUNT(*) FROM records")==64&&number(db,"SELECT COUNT(*) FROM packages")==1 : "Shared immutable profiles/programs are stored once";
        assert number(db,"SELECT COUNT(*) FROM records WHERE json_extract(value,'$.package') IS NOT NULL")==0;
        execute(db,"CREATE TABLE changed_records(id TEXT)");execute(db,"CREATE TRIGGER changed AFTER UPDATE ON records BEGIN INSERT INTO changed_records VALUES(new.id); END");
        try(var journal=new TaskJournal(db,legacy,collection)){
            JsonObject loaded=journal.read();assert loaded.equals(input);var rows=loaded.getAsJsonObject(collection);
            journal.write(loaded);assert number(db,"SELECT COUNT(*) FROM changed_records")==0 : "A no-op checkpoint writes no history rows";
            rows.getAsJsonObject(id(1)).getAsJsonObject("state").addProperty("confirmed",13);journal.write(loaded);
            assert number(db,"SELECT COUNT(*) FROM changed_records")==1 : "One receipt changes exactly one row, not all retained jobs";
            rows.getAsJsonObject(id(1)).getAsJsonObject("package").addProperty("entry","other");journal.write(loaded);
            assert number(db,"SELECT COUNT(*) FROM packages")==2 : "A package edited in place cannot escape change detection";
            assert rows.getAsJsonObject(id(2)).getAsJsonObject("package").get("entry").getAsString().equals("main") : "Shared storage never aliases mutable caller objects";
            rows.remove(id(64));rows.add(id(65),rows.getAsJsonObject(id(2)).deepCopy());loaded.remove("original");journal.write(loaded);
            JsonObject expected=loaded.deepCopy();assert journal.read().equals(expected) : "Deletion, insertion, order, profile recovery and pending receipt fields round-trip exactly";
            assert new ArrayList<>(journal.read().getAsJsonObject(collection).keySet()).getLast().equals(id(65));
            rows=loaded.getAsJsonObject(collection);for(var e:rows.entrySet())e.getValue().getAsJsonObject().remove("package");journal.write(loaded);
            assert number(db,"SELECT COUNT(*) FROM packages")==0 : "Packages retire only when no retained run references them";
        }
        // The initialized database remains authoritative over an old, valid legacy file.
        try(var journal=new TaskJournal(db,legacy,collection)){assert journal.read().getAsJsonObject(collection).has(id(65));}
    }
    private static void rollback()throws Exception{
        Path dir=Files.createTempDirectory("task-journal-rollback"),db=dir.resolve("tasks.sqlite"),legacy=dir.resolve("tasks.json");JsonObject baseline=root("runs");
        try(var journal=new TaskJournal(db,legacy,"runs")){journal.write(baseline);}
        execute(db,"CREATE TRIGGER block_second BEFORE UPDATE ON records WHEN new.id='"+id(2)+"' BEGIN SELECT RAISE(ABORT,'synthetic checkpoint failure'); END");
        try(var journal=new TaskJournal(db,legacy,"runs")){
            JsonObject next=journal.read();next.getAsJsonObject("runs").getAsJsonObject(id(1)).addProperty("status","Changed");next.getAsJsonObject("runs").getAsJsonObject(id(2)).addProperty("status","Changed");next.addProperty("original","changed");
            rejects(()->journal.write(next));rejects(()->journal.write(next));
            try(var reader=new TaskJournal(db,legacy,"runs")){assert reader.read().equals(baseline) : "A failed commit preserves all old rows, package references and original settings; failed cache state is never acknowledged";}
        }
    }
    private static void missingDatabase()throws Exception{
        Path dir=Files.createTempDirectory("task-journal-missing"),db=dir.resolve("tasks.sqlite"),legacy=dir.resolve("tasks.json");JsonObject input=root("runs");TaskFiles.write(legacy,input);
        try(var journal=new TaskJournal(db,legacy,"runs")){
            journal.write(journal.read());Files.delete(db);rejects(()->journal.write(input));
        }
        try(var restarted=new TaskJournal(db,legacy,"runs")){rejects(restarted::read);}
        Path replaced=dir.resolve("other.sqlite");
        try(var journal=new TaskJournal(replaced,dir.resolve("none.json"),"runs")){
            journal.write(input);execute(replaced,"UPDATE records SET value=json_set(value,'$.status','External') WHERE id='"+id(1)+"'");rejects(()->journal.write(input));
        }
    }
    private static void malformed()throws Exception{
        Path dir=Files.createTempDirectory("task-journal-malformed"),db=dir.resolve("tasks.sqlite"),legacy=dir.resolve("tasks.json");JsonObject bad=new JsonObject();bad.addProperty("version",1);TaskFiles.write(legacy,bad);
        try(var journal=new TaskJournal(db,legacy,"runs")){rejects(journal::read);}assert !Files.exists(db)&&TaskFiles.read(legacy).equals(bad);
        try(var journal=new TaskJournal(db,legacy,"runs")){rejects(()->journal.write(root("tasks")));}
        Files.delete(legacy);try(var journal=new TaskJournal(db,legacy,"runs")){journal.write(root("runs"));}
        try(var wrong=new TaskJournal(db,legacy,"tasks")){rejects(wrong::read);}
        execute(db,"UPDATE packages SET value='{}'");try(var invalid=new TaskJournal(db,legacy,"runs")){rejects(invalid::read);}
    }
    private static void benchmark()throws Exception{
        Path dir=Files.createTempDirectory("task-journal-benchmark");JsonObject input=root("runs"),state=input.getAsJsonObject("runs").getAsJsonObject(id(1)).getAsJsonObject("state");
        try(var journal=new TaskJournal(dir.resolve("tasks.sqlite"),dir.resolve("none.json"),"runs")){
            journal.write(input);long start=System.nanoTime();
            for(int i=0;i<40;i++){state.addProperty("confirmed",i);journal.write(input);}long sql=System.nanoTime()-start;start=System.nanoTime();
            for(int i=0;i<10;i++){state.addProperty("confirmed",i);TaskFiles.write(dir.resolve("legacy.json"),input);}long json=System.nanoTime()-start;
            System.out.printf(Locale.ROOT,"Synthetic 64-run checkpoint: SQLite %.3f ms/write; whole-history JSON %.3f ms/write (local filesystem, not gameplay throughput).%n",sql/40e6,json/10e6);
        }
    }
}
