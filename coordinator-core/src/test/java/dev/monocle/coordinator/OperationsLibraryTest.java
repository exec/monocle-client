package dev.monocle.coordinator;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

final class OperationsLibraryTest {
    static void run() throws Exception {
        Path file=Files.createTempDirectory("monocle-operations-check-").resolve("library.json");OperationsLibrary library=new OperationsLibrary(file);
        JsonObject original=library.get("highway-default");String id=UUID.randomUUID().toString();
        assert original.get("name").getAsString().equals("6b6t Highway Builder");
        String highwaySettings=original.getAsJsonObject("package").getAsJsonObject("profiles").getAsJsonObject("Current").getAsJsonObject("highway-builder").get("settings").getAsString();
        assert highwaySettings.contains("keep-shulkers',value:0b") && highwaySettings.contains("experimental-managed-inventory',value:1b");
        JsonObject copy=library.save(id,"My highway","Highways/Nether",original.getAsJsonObject("package"));
        copy.getAsJsonObject("package").addProperty("entry","bad");assert !library.get(id).getAsJsonObject("package").get("entry").getAsString().equals("bad");
        assert new OperationsLibrary(file).get(id).get("name").getAsString().equals("My highway");
        boolean rejected=false;try{library.delete("highway-default");}catch(IllegalArgumentException e){rejected=true;}assert rejected;
        JsonObject invalid=original.getAsJsonObject("package").deepCopy();invalid.getAsJsonObject("programs").getAsJsonObject("highway-default").addProperty("script","not lua !");
        rejected=false;try{library.save(id,"Changed","Highways/Nether",invalid);}catch(RuntimeException e){rejected=true;}assert rejected;assert library.get(id).get("name").getAsString().equals("My highway");
        JsonObject draft=new JsonObject();draft.addProperty("id",UUID.randomUUID().toString());draft.addProperty("name","West highway");draft.addProperty("server","test.invalid");draft.addProperty("dimension","minecraft:the_nether");draft.addProperty("priority",0);draft.add("args",new JsonObject());draft.add("package",original.get("package"));
        library.saveDraft(draft);assert library.drafts().size()==1;library.delete(id);assert library.draft(draft.get("id").getAsString()).has("package") : "Saved jobs hold immutable workflow snapshots";
        assert new OperationsLibrary(file).drafts().size()==1;library.deleteDraft(draft.get("id").getAsString());assert library.drafts().isEmpty();
        System.out.println("Operations library checks passed: portable packages, built-in protection, validation, snapshots, persistence and unassigned jobs.");
    }
}
