package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Durable, host/account-scoped solo exports. A later read of the same cell replaces its queued read. */
public final class StashExports {
    private StashExports(){}
    private static Path directory(Path root,String owner,String worker){
        UUID.fromString(worker);if(owner.isBlank())throw new IllegalArgumentException("Missing stash export host");
        return root.resolve("stash-export-outbox-sqlite-v1").resolve(TaskFiles.hash(owner+"\n"+worker));
    }
    private static Path file(Path root,JsonObject entry){
        JsonObject m=entry.getAsJsonObject("message");String stash=TaskWire.text(m,"scope")+"\n"+TaskWire.text(m.getAsJsonObject("stash"),"name");
        String cell=TaskWire.text(m,"type").equals("task-stash-definition")?"0-definition":"1-"+TaskFiles.hash(StashCatalog.containerKey(StashCatalog.readObservation(m)));
        return directory(root,TaskWire.text(entry,"owner"),TaskWire.text(entry,"worker")).resolve(TaskFiles.hash(stash)).resolve(cell+".json");
    }
    public static synchronized void enqueue(Path root,String owner,String worker,JsonObject message){
        JsonObject m=message.deepCopy(),p=StashCatalog.plan(m.getAsJsonObject("stash"));
        String type=TaskWire.text(m,"type");if(!type.equals("task-stash-definition")&&!type.equals("task-stash-import"))throw new IllegalArgumentException("Unsupported stash export");
        if(type.equals("task-stash-import"))StashCatalog.observation(p,StashCatalog.readObservation(m));
        m.addProperty("exportId",UUID.randomUUID().toString());TaskFiles.jsonBytes(m,CrewFrames.MAX_BYTES);
        JsonObject entry=new JsonObject();entry.addProperty("owner",owner);entry.addProperty("worker",worker);entry.add("message",m);
        TaskFiles.write(file(root,entry),entry);
    }
    public static synchronized JsonObject next(Path root,String owner,String worker){
        return next(root,owner,worker,"");
    }
    public static synchronized JsonObject next(Path root,String owner,String worker,String scope){
        Path dir=directory(root,owner,worker);if(!Files.exists(dir))return null;
        try(var stashes=Files.list(dir)){
            for(Path stash:stashes.filter(Files::isDirectory).toList()){
                Path definition=stash.resolve("0-definition.json");JsonObject first=readQueued(root,definition,owner,worker,scope);if(first!=null)return first;
                try(var files=Files.list(stash)){
                    var paths=files.filter(p->p.getFileName().toString().endsWith(".json")&&!p.equals(definition)).iterator();while(paths.hasNext()){
                        Path path=paths.next();JsonObject entry=readQueued(root,path,owner,worker,scope);if(entry!=null)return entry;
                    }
                }
            }
        }catch(IOException e){throw new IllegalStateException("Could not read stash export outbox",e);}return null;
    }
    private static JsonObject readQueued(Path root,Path path,String owner,String worker,String scope){
        if(!Files.exists(path))return null;
        JsonObject entry=TaskFiles.read(path);if(entry.has("conflict"))return null;
        if(!owner.equals(TaskWire.text(entry,"owner"))||!worker.equals(TaskWire.text(entry,"worker"))||!file(root,entry).equals(path))throw new IllegalStateException("Invalid stash export journal; original retained");
        return scope.isEmpty()||scope.equals(TaskWire.text(entry.getAsJsonObject("message"),"scope"))?entry:null;
    }
    public static synchronized void acknowledge(Path root,JsonObject entry,JsonObject ack){
        if(!TaskWire.text(entry.getAsJsonObject("message"),"exportId").equals(TaskWire.text(ack,"exportId")))return;
        Path path=file(root,entry);JsonObject current=TaskFiles.read(path);
        if(current.isEmpty()||!TaskWire.text(current.getAsJsonObject("message"),"exportId").equals(TaskWire.text(ack,"exportId")))return;
        switch(TaskWire.text(ack,"outcome")){
            case "accepted" -> {try{Files.delete(path);}catch(IOException e){throw new IllegalStateException("Could not retire acknowledged stash export",e);}}
            case "conflict" -> {current.addProperty("conflict",TaskWire.text(ack,"detail"));TaskFiles.write(path,current);}
            case "deferred" -> {}
            default -> throw new IllegalArgumentException("Invalid stash export receipt");
        }
    }
}
