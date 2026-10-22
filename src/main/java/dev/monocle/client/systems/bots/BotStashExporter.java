package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import dev.monocle.coordinator.*;
import java.util.concurrent.*;
import static dev.monocle.client.MonocleClient.mc;

/** Receipt-based background export; scan movement waits only for the local durable checkpoint. */
public final class BotStashExporter {
    private final Bots bots;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"stash-export-writer");t.setDaemon(true);return t;});
    private CompletableFuture<JsonObject> loading;
    private CompletableFuture<Void> saving;
    private JsonObject flight;
    private String readyOwner="",loadedOwner="",loadedWorker="";
    private long sentAt,loadAt,warningAt;
    public BotStashExporter(Bots bots){this.bots=bots;}
    public String owner(){return bots.ipAddress.get()+":"+bots.serverPort.get()+"#"+SwarmConnection.credentialSelector(bots.crewKey.get());}
    public void ready(JsonObject catalog,String credential){readyOwner=catalog.has("stashExportProtocol")&&catalog.get("stashExportProtocol").getAsInt()==1?bots.ipAddress.get()+":"+bots.serverPort.get()+"#"+credential:"";}
    public void acknowledge(JsonObject ack,String credential){
        String source=bots.ipAddress.get()+":"+bots.serverPort.get()+"#"+credential;
        if(flight==null||saving!=null||!source.equals(loadedOwner)||!TaskWire.text(flight.getAsJsonObject("message"),"exportId").equals(TaskWire.text(ack,"exportId")))return;
        if(TaskWire.text(ack,"outcome").equals("deferred")){sentAt=System.currentTimeMillis();return;}
        JsonObject entry=flight.deepCopy(),receipt=ack.deepCopy();saving=CompletableFuture.runAsync(()->StashExports.acknowledge(MonocleClient.FOLDER.toPath(),entry,receipt),writer);
        if(TaskWire.text(ack,"outcome").equals("conflict"))warn("Stash export retained for review: "+TaskWire.text(ack,"detail"));
    }
    private void warn(String detail){if(System.currentTimeMillis()-warningAt>5000){warningAt=System.currentTimeMillis();MonocleClient.LOG.warn("[Workers] {}",detail);}}
    public void tick(){
        if(saving!=null){if(!saving.isDone())return;try{saving.join();flight=null;loadAt=0;}catch(RuntimeException e){warn("Stash export receipt save failed; retry retained: "+e.getMessage());}saving=null;}
        if(!bots.isWorker()){readyOwner="";flight=null;return;}
        var manager=dev.monocle.client.systems.modules.Modules.get().get(dev.monocle.client.systems.modules.world.StashManager.class);
        if(manager==null||!manager.exportsScans())return;
        if(mc.player==null||mc.level==null)return;
        String owner=owner(),worker=mc.player.getUUID().toString();
        if(!owner.equals(loadedOwner)||!worker.equals(loadedWorker)){flight=null;loadedOwner=owner;loadedWorker=worker;}
        if(loading!=null){if(!loading.isDone())return;try{JsonObject entry=loading.join();if(entry!=null&&owner.equals(TaskWire.text(entry,"owner"))&&worker.equals(TaskWire.text(entry,"worker"))){flight=entry;sentAt=0;}}catch(RuntimeException e){warn("Stash exports retained; outbox load failed: "+e.getMessage());}loading=null;}
        if(!owner.equals(readyOwner))return;
        if(mc.level==null)return;String scope=(mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip)+"\n"+mc.level.dimension().identifier();
        if(flight!=null&&!scope.equals(TaskWire.text(flight.getAsJsonObject("message"),"scope"))){flight=null;loadAt=0;}
        if(flight==null){if(System.currentTimeMillis()-loadAt<1000)return;loadAt=System.currentTimeMillis();loading=CompletableFuture.supplyAsync(()->StashExports.next(MonocleClient.FOLDER.toPath(),owner,worker,scope),writer);return;}
        if(System.currentTimeMillis()-sentAt<5000)return;
        if(bots.sendToHost(flight.getAsJsonObject("message")))sentAt=System.currentTimeMillis();
    }
}
