package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.Files;
import java.util.*;

final class HostileWarningsTest {
    static void run() throws Exception {
        KnownHostiles hostiles = new KnownHostiles(Files.createTempDirectory("hostile-warnings-").resolve("hostiles.json"));
        UUID enemy = UUID.randomUUID(), observer = UUID.randomUUID(); String scope = "example.org\nminecraft:the_nether";
        JsonObject player = JsonParser.parseString("{uuid:'" + enemy + "',name:'Enemy',trusted:false,x:1,y:116,z:3}").getAsJsonObject();
        JsonArray players = new JsonArray(); players.add(player);
        PlayerSightings view = new PlayerSightings(); view.observe(scope, players, 1000);
        JsonObject report = new JsonObject(); report.addProperty("scope", scope); report.add("playerChecks", view.checks(1100));
        HostileWarnings warnings = new HostileWarnings();
        assert warnings.observe(observer, "Observer", "CrewA", report, hostiles, Set.of(), 900_000).isEmpty();
        HostileWarnings.validateReceipt(HostileWarnings.receipt(report, hostiles.revision()));
        assert HostileWarnings.receipt(report, hostiles.revision()).get("checked").getAsInt() == 1;
        JsonObject entry = hostiles.add("example.org", "Enemy", enemy.toString());
        assert warnings.observe(observer, "Observer", "CrewA", report, hostiles, Set.of(), 900_000).size() == 1 : "Catalog changes recheck a still-visible player, regardless of clock skew or entrance history";
        assert !hostiles.enabled() : "Guard off still warns";
        var first = warnings.pending("CrewA", hostiles, 900_001).getFirst(); HostileWarnings.validate(first);
        warnings.delivered("CrewA", first); assert warnings.pending("CrewA", hostiles, 900_002).isEmpty();
        assert warnings.pending("CrewB", hostiles, 900_002).size() == 1 : "Warnings cross crew boundaries";
        assert warnings.observe(UUID.randomUUID(), "Other", "CrewB", report, hostiles, Set.of(), 901_000).isEmpty();
        assert TaskWire.text(warnings.snapshot(hostiles,901_001).get(0).getAsJsonObject(),"observerName").equals("Other") : "WebUI alarms retain the freshest observer evidence without creating a new warning";
        assert warnings.pending("CrewA", hostiles, 901_001).isEmpty() : "Multiple reporters and repeated checks do not repeat the warning";
        warnings.retainRecipients(Set.of("CrewB")); assert warnings.pending("CrewA", hostiles, 901_002).size() == 1 : "Reconnecting recipients receive the active alarm";
        var offWarning=warnings.pending("CrewB",hostiles,901_002).getFirst();warnings.delivered("CrewB",offWarning);
        hostiles.policy(true);assert warnings.pending("CrewB",hostiles,901_002).size()==1 : "Arming the guard reissues still-active alarms, even after an earlier warning-only delivery";
        hostiles.remove(TaskWire.text(entry, "id")); assert warnings.pending("CrewB", hostiles, 901_003).isEmpty() : "Forgiveness invalidates pending warnings";
        hostiles.add("example.org", "Enemy", enemy.toString());
        report.addProperty("inLobby", true); assert warnings.observe(observer,"Observer","Crew",report,hostiles,Set.of(),902_000).isEmpty();
        assert HostileWarnings.receipt(report, hostiles.revision()).get("checked").getAsInt() == 0;
        report.addProperty("inLobby", false); player.addProperty("trusted", true); view.observe(scope, players, 2000); report.add("playerChecks", view.checks(2000));
        assert warnings.observe(observer,"Observer","Crew",report,hostiles,Set.of(),902_000).isEmpty();
        player.addProperty("trusted", false); view.observe(scope, players, 3000); report.add("playerChecks", view.checks(3000));
        assert warnings.observe(observer,"Observer","Crew",report,hostiles,Set.of(enemy),902_000).isEmpty();
        assert warnings.observe(enemy,"Observer","Crew",report,hostiles,Set.of(),902_000).isEmpty();
        report.addProperty("scope", "elsewhere.org\nminecraft:the_nether");
        assert warnings.observe(observer,"Observer","Crew",report,hostiles,Set.of(),902_000).isEmpty();
        report.addProperty("scope", scope); report.add("playerChecks", view.checks(9000));
        assert warnings.observe(observer,"Observer","Crew",report,hostiles,Set.of(),902_000).isEmpty() : "Stale samples cannot trigger safety actions";
        report.remove("playerChecks");report.add("playerSightings",view.snapshot());
        assert warnings.observe(observer,"Observer","Crew",report,hostiles,Set.of(),902_000).isEmpty() : "Historical sightings cannot trigger safety actions";
        view.observe(scope, players, 10_000); report.add("playerChecks", view.checks(10_000));
        assert warnings.observe(observer,"Observer","Crew",report,hostiles,Set.of(),902_000).size()==1;
        assert warnings.pending("CrewB",hostiles,912_000).isEmpty() : "Alarms expire without a fresh check";
        view.resetView(); assert view.checks(10_001).getAsJsonArray("sightings").isEmpty() : "World/lobby changes clear only current visibility";
        JsonObject invalid=first.deepCopy();invalid.addProperty("remainingMs",10_001);
        try{HostileWarnings.validate(invalid);throw new AssertionError("Invalid alarm lifetime accepted");}catch(IllegalArgumentException expected){}
        invalid=first.deepCopy();invalid.getAsJsonObject("warning").addProperty("server","different.org");
        try{HostileWarnings.validate(invalid);throw new AssertionError("Mismatched server accepted");}catch(IllegalArgumentException expected){}
        JsonArray crowd=new JsonArray();for(int i=0;i<65;i++){JsonObject p=player.deepCopy();p.addProperty("uuid",UUID.randomUUID().toString());crowd.add(p);}
        Set<String> checked=new HashSet<>();for(int i=0;i<3;i++){view.observe(scope,crowd,11_000+i*1000);var checks=view.checks(11_000+i*1000);PlayerSightings.validateChecks(checks);checks.getAsJsonArray("sightings").forEach(s->checked.add(TaskWire.text(s.getAsJsonObject().getAsJsonObject("player"),"uuid")));}
        assert checked.size()==65 : "Crowd batches rotate instead of starving later players";
        PlayerSightings emergency=new PlayerSightings();emergency.observe(scope,new JsonArray(),15_000);emergency.observe(scope,players,15_010);
        assert emergency.checks(15_010).getAsJsonArray("sightings").size()==1 : "A cached local guard's pre-logout announcement must include the new hostile, even between normal samples";
        warnings.observe(observer,"Observer","Crew",report,hostiles,Set.of(),920_000);
        assert warnings.pending("CrewB",hostiles,919_000).isEmpty():"A host clock rollback must not lengthen or replay an old alarm";
        System.out.println("Host-wide warning checks passed: fresh/negative checks, clock skew, cross-crew delivery, dedup/reconnect, forgiveness, lobby/trust/server exclusions, expiry and crowd rotation.");
    }
}
