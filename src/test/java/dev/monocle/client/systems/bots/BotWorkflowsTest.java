package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.*;
import static dev.monocle.client.systems.bots.BotWorkflows.Action.*;

/** Real persistence, nested-call validation and mixed-duty assignment checks. Run by botsCheck. */
final class BotWorkflowsTest {
    static void run() throws Exception {
        var directory = Files.createTempDirectory("monocle-workflow-check-");
        try {
            var path = directory.resolve("workflows.json");
            var library = new BotWorkflows(path);
            assert library.all().size() == 14;
            assert library.get("task-stash-scan").script().contains("bot.stash_scan");
            assert library.get("task-stash-resupply").script().contains("bot.stash_resupply");
            var original = library.compile(BotWorkflows.DEFAULT_ID);
            assert original.get("duty").getAsString().equals("Build");
            assert original.getAsJsonArray("actions").get(2).getAsString().equals("InventoryShulkers");
            bad(() -> library.delete(BotWorkflows.DEFAULT_ID));
            bad(() -> library.save(BotWorkflows.DEFAULT_ID, "Changed", "Highway Builder", List.of(new BotWorkflows.Step(Paving, ""))));
            var copy = library.duplicate(BotWorkflows.DEFAULT_ID, "My highway", "Highway Builder/Custom");
            bad(() -> library.duplicate(BotWorkflows.DEFAULT_ID, "my highway", "Highway Builder/Custom"));
            assert BotWorkflows.legacyPlan("Repair").get("duty").getAsString().equals("Pave");
            var captured = library.compile(copy.id());
            var supplies = library.duplicate("highway-supplies", "Echests first", "Highway Builder/Supplies");
            library.save(supplies.id(), supplies.name(), supplies.folder(), List.of(new BotWorkflows.Step(EnderChestContents, ""), new BotWorkflows.Step(InventoryShulkers, "")));
            library.save(copy.id(), copy.name(), copy.folder(), List.of(new BotWorkflows.Step(Paving, ""), new BotWorkflows.Step(Call, supplies.id())));
            var plan = library.compile(copy.id());
            assert plan.get("duty").getAsString().equals("Pave");
            assert plan.getAsJsonArray("actions").get(1).getAsString().equals("EnderChestContents") : "Source order must survive nested calls";
            assert captured.get("duty").getAsString().equals("Build") : "Editing a library entry cannot mutate a captured job";
            assert library.compile(BotWorkflows.DEFAULT_ID).equals(original) : "Built-ins never follow edits to their duplicates";
            String saved = Files.readString(path);
            bad(() -> library.save(supplies.id(), supplies.name(), supplies.folder(), List.of(new BotWorkflows.Step(Call, copy.id()))));
            bad(() -> library.save(copy.id(), copy.name(), "../bad", copy.steps()));
            bad(() -> library.save(copy.id(), copy.name(), copy.folder(), List.of(new BotWorkflows.Step(Paving, ""), new BotWorkflows.Step(Call, "highway-pave"))));
            bad(() -> library.save(copy.id(), copy.name(), copy.folder(), List.of(new BotWorkflows.Step(Call, UUID.randomUUID().toString()))));
            bad(() -> library.delete(supplies.id()));
            assert Files.readString(path).equals(saved) : "Rejected edits leave the disk and library intact";
            var restored = new BotWorkflows(path);
            assert restored.compile(copy.id()).equals(plan);
            plan.addProperty("duty", "Build");
            assert BotWorkflows.checkedPlan(plan).get("duty").getAsString().equals("Pave") : "Never trust a forged duty field";
            bad(() -> BotWorkflows.operation(library.compile("highway-supplies")));

            UUID excavator = UUID.randomUUID(), paver = UUID.randomUUID();
            JsonObject job = new JsonObject(); job.add("workflow", original);
            JsonObject overrides = new JsonObject(); overrides.add(excavator.toString(), library.compile("highway-excavate")); overrides.add(paver.toString(), library.compile("highway-pave"));
            job.add("memberWorkflows", overrides);
            Bots.applyWorkflowDuties(job, Set.of(excavator, paver));
            assert job.getAsJsonObject("duties").get(excavator.toString()).getAsString().equals("Excavate");
            assert job.getAsJsonObject("duties").get(paver.toString()).getAsString().equals("Pave");
            bad(() -> Bots.applyWorkflowDuties(job, Set.of(paver)));
            job.add("workflow", library.compile("highway-pave"));
            bad(() -> Bots.applyWorkflowDuties(job, Set.of(excavator, paver)));

            library.delete(copy.id()); library.delete(supplies.id());
            assert new BotWorkflows(path).all().size() == 14;
            Files.writeString(path, "{broken");
            var broken = new BotWorkflows(path);
            bad(broken::all); bad(() -> broken.duplicate(BotWorkflows.DEFAULT_ID, "No overwrite", "Highway Builder"));
            assert Files.readString(path).equals("{broken");
        } finally {
            try (var files = Files.walk(directory)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    private static void bad(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid workflow must be rejected"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
