package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

final class IncidentTimelineTest {
    static void run() throws Exception {
        UUID worker=UUID.randomUUID(),player=UUID.randomUUID(),crew=UUID.randomUUID();
        String scope="example.org\nminecraft:the_nether";
        JsonObject p=new JsonObject();p.addProperty("uuid",player.toString());p.addProperty("name","Visitor");p.addProperty("trusted",false);
        for(String key:List.of("x","y","z"))p.addProperty(key,116);
        JsonArray players=new JsonArray();players.add(p);
        PlayerSightings sightings=new PlayerSightings();sightings.observe(scope,players,1000);
        sightings.observe(scope,players,2000);assert sightings.snapshot().size()==1 : "Standing in view is not a new sighting";
        sightings.observe(scope,new JsonArray(),3000);sightings.observe(scope,players,4000);assert sightings.snapshot().size()==1 : "Brief flicker is debounced";
        sightings.observe(scope,new JsonArray(),14000);sightings.observe(scope,players,15000);assert sightings.snapshot().size()==2;
        sightings.resetView();assert sightings.snapshot().size()==2 : "World loss cannot erase unreported evidence";
        sightings.observe(scope,players,16000);assert sightings.snapshot().size()==3;
        PlayerSightings.validate(sightings.snapshot());
        Path file=Files.createTempDirectory("incidents-check-").resolve("incidents.telemetry.jsonl");
        KnownHostiles hostiles=new KnownHostiles(file.resolveSibling("hostiles.json"));
        List<JsonObject> written=new ArrayList<>();IncidentTimeline timeline=new IncidentTimeline(written::add);
        JsonObject report=new JsonObject();report.add("playerSightings",sightings.snapshot());
        report.addProperty("inLobby",true);timeline.observe(worker,"Worker","Crew",report,Set.of(),hostiles);assert written.isEmpty() : "Lobby reports never enter the timeline";report.addProperty("inLobby",false);
        timeline.observe(worker,"Worker","Crew",report,Set.of(),hostiles);timeline.observe(worker,"Worker","Crew",report,Set.of(),hostiles);
        assert written.size()==3;assert timeline.page(null,1,"sighting","").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("evidence").get("classification").getAsString().equals("neutral");
        JsonObject first=timeline.page(null,1,"sighting","");assert first.get("hasMore").getAsBoolean();
        String cursor=first.get("nextCursor").getAsString();assert timeline.page(cursor,100,"sighting","").getAsJsonArray("items").size()==2;
        assert timeline.page(null,100,"damage","").getAsJsonArray("items").isEmpty();assert timeline.page(null,100,"","Visitor").getAsJsonArray("items").size()==3;
        hostiles.add("example.org","Visitor",player.toString());timeline.observe(worker,"Worker","Crew",report,Set.of(),hostiles);assert written.size()==3 : "Classification changes must not duplicate a visit";
        report.add("combat",KnownHostilesTest.evidence(player,20));timeline.observe(worker,"Worker","Crew",report,Set.of(),hostiles);assert written.size()==4;
        timeline.observe(worker,"Worker","Crew",report,Set.of(),hostiles);assert written.size()==4;
        IncidentTimeline excluded=new IncidentTimeline(_->{});excluded.observe(worker,"Worker","Crew",report,Set.of(player),hostiles);assert excluded.page(null,100,"sighting","").getAsJsonArray("items").isEmpty();
        Files.writeString(file,written.stream().map(JsonObject::toString).collect(java.util.stream.Collectors.joining("\n"))+"\ncorrupt line\n");
        IncidentTimeline restored=new IncidentTimeline(file);assert restored.summary().get("retained").getAsInt()==4;assert restored.summary().get("unreadableRecords").getAsInt()==1;
        restored.observe(worker,"Worker","Crew",report,Set.of(),hostiles);assert restored.summary().get("retained").getAsInt()==4 : "Restart must not replay the latest evidence";
        assert restored.page("a".repeat(64),1,"","").get("cursorExpired").getAsBoolean();
        IncidentTimeline failedSink=new IncidentTimeline(_->{throw new IllegalStateException("Disk failed");});failedSink.observe(worker,"Worker","Crew",report,Set.of(),hostiles);assert failedSink.summary().get("retained").getAsInt()==4;
        for(int i=0;i<2100;i++){report.remove("playerSightings");report.add("combat",KnownHostilesTest.evidence(player,100+i));timeline.observe(worker,"Worker","Crew",report,Set.of(),hostiles);}
        assert timeline.summary().get("retained").getAsInt()==2048;assert timeline.page(cursor,1,"","").get("cursorExpired").getAsBoolean();
        JsonArray invalid=sightings.snapshot();invalid.get(0).getAsJsonObject().addProperty("id","wrong");
        try{PlayerSightings.validate(invalid);throw new AssertionError("Invalid sighting accepted");}catch(IllegalArgumentException expected){}
        System.out.println("Incident checks passed: entry debounce, world reset, deduplication, filtering, worker exclusion, restart, bad records, failed logging and bounded retention.");
    }
}
