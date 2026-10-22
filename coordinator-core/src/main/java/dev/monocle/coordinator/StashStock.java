package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;

/** Transfer-adjusted estimates are independent of the last observed contents of each chest. */
final class StashStock {
    private StashStock() { }

    static boolean begin(JsonObject db) {
        if (db.has("transferStock")) return false;
        JsonObject stock = new JsonObject();
        stock.add("counts", counts(db));
        stock.add("items", StashCatalog.summary(db).get("items").deepCopy());
        stock.add("depositReceipts", new JsonObject());
        stock.add("scanReads", new JsonObject());
        stock.addProperty("since", System.currentTimeMillis());
        stock.addProperty("uncertain", true);
        db.add("transferStock", stock);
        return true;
    }

    private static JsonObject counts(JsonObject db) {
        JsonObject counts = new JsonObject();
        for (var entry : KitCategories.stock(db).entrySet()) {
            JsonObject row = entry.getValue().getAsJsonObject(), count = new JsonObject();
            count.add("complete", row.get("complete").deepCopy());
            count.add("incomplete", row.get("incomplete").deepCopy());
            counts.add(entry.getKey(), count);
        }
        return counts;
    }

    static void change(JsonObject db, JsonArray boxes, int sign) {
        begin(db);
        JsonObject stock = db.getAsJsonObject("transferStock"), counts = stock.getAsJsonObject("counts");
        for (JsonElement value : boxes) {
            JsonObject box = value.getAsJsonObject();
            String id = KitTypes.canonical(db, box.has("kitTypeId") ? TaskWire.text(box,"kitTypeId") : StashCatalog.kitTypeId(box));
            JsonObject type = db.getAsJsonObject("kitTypes").getAsJsonObject(id);
            long quantity = sign < 0 ? 1 : box.has("quantity") ? box.get("quantity").getAsLong() : 1;
            String outer=box.has("item")?TaskWire.text(box,"item"):type==null?"":TaskWire.text(type,"item");
            if(!outer.isEmpty()){
                JsonObject items=stock.getAsJsonObject("items");long next=(items.has(outer)?items.get(outer).getAsLong():0)+sign*quantity;
                if(next<0)stock.addProperty("uncertain",true);items.addProperty(outer,Math.max(0,next));
            }
            if(box.has("items"))for(var item:box.getAsJsonObject("items").entrySet()){
                JsonObject items=stock.getAsJsonObject("items");long next=(items.has(item.getKey())?items.get(item.getKey()).getAsLong():0)+sign*quantity*item.getValue().getAsLong();
                if(next<0)stock.addProperty("uncertain",true);items.addProperty(item.getKey(),Math.max(0,next));
            }
            if (type == null || !box.has("items")) { stock.addProperty("uncertain", true); continue; }
            JsonObject count = counts.has(id) ? counts.getAsJsonObject(id) : new JsonObject();
            String field = StashCatalog.kitIncomplete(type.getAsJsonObject("items"), box.getAsJsonObject("items")) ? "incomplete" : "complete";
            long next = (count.has(field) ? count.get(field).getAsLong() : 0) + sign * quantity;
            if (next < 0) stock.addProperty("uncertain", true);
            count.addProperty(field, Math.max(0, next));
            counts.add(id, count);
        }
        stock.add("scanReads", new JsonObject());
    }

    static JsonObject estimates(JsonObject db) {
        return db.has("transferStock") ? db.getAsJsonObject("transferStock").getAsJsonObject("counts").deepCopy() : new JsonObject();
    }

    static void observed(JsonObject db, JsonObject plan, JsonObject observation) {
        if (!db.has("transferStock")) return;
        JsonObject stock = db.getAsJsonObject("transferStock");
        stock.addProperty("uncertain", true);
        if (!TaskWire.text(plan,"type").equals("StashScan")
            || !TaskWire.text(plan,"scanMode").equals("Full") || TaskWire.flag(plan,"lazyMode")) return;
        JsonObject reads = stock.getAsJsonObject("scanReads");
        String status = TaskWire.text(observation,"status");
        if (status.equals("removed") || status.equals("observed") && !TaskWire.flag(observation,"inferred"))
            reads.addProperty(StashCatalog.containerKey(observation), true);
        else reads.remove(StashCatalog.containerKey(observation));
    }

    static boolean reconcile(JsonObject db) {
        if(!db.has("transferStock"))return false;
        JsonObject stock=db.getAsJsonObject("transferStock"),reads=stock.getAsJsonObject("scanReads");
        for (var entry : db.getAsJsonObject("containers").entrySet()) {
            if (TaskWire.text(entry.getValue().getAsJsonObject(),"status").equals("removed")) continue;
            if (!reads.has(entry.getKey())) return false;
        }
        stock.add("counts", counts(db));
        stock.add("items", StashCatalog.summary(db).get("items").deepCopy());
        stock.addProperty("uncertain", false);
        stock.addProperty("reconciledAt", System.currentTimeMillis());
        stock.add("scanReads", new JsonObject());
        return true;
    }
}
