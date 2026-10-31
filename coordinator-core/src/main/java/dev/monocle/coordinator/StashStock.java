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
        if(db.has("columnFill"))for(JsonElement value:boxes){
            JsonObject box=value.getAsJsonObject();if(!box.has("x")||!box.has("y")||!box.has("z"))continue;
            for(JsonElement entry:db.getAsJsonObject("columnFill").asMap().values()){
                JsonObject row=entry.getAsJsonObject(),column=row.getAsJsonObject("column");
                if(!StashCatalog.columnContains(column,box.get("x").getAsInt(),box.get("y").getAsInt(),box.get("z").getAsInt()))continue;
                long next=row.get("kits").getAsLong()+sign*(box.has("quantity")?box.get("quantity").getAsLong():1);
                if(next<0||next>row.get("capacity").getAsLong())row.addProperty("uncertain",true);row.addProperty("kits",Math.max(0,next));row.addProperty("updatedAt",System.currentTimeMillis());
            }
        }
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

    /** Explicit empty storage starts at zero, never by erasing a known populated chest. */
    static void startColumn(JsonObject db,JsonObject column){
        if(!TaskWire.flag(db.getAsJsonObject("bounds"),"assumeEmpty"))return;
        String key=column.get("x")+","+column.get("z");JsonObject fills=db.has("columnFill")?db.getAsJsonObject("columnFill"):new JsonObject();
        long capacity=0;boolean empty=true;
        for(JsonElement entry:db.getAsJsonObject("containers").asMap().values()){
            JsonObject c=entry.getAsJsonObject();if(TaskWire.text(c,"status").equals("removed")||!Set.of("minecraft:chest","minecraft:trapped_chest").contains(TaskWire.text(c,"block"))
                ||!StashCatalog.columnContains(column,c.get("x").getAsInt(),c.get("y").getAsInt(),c.get("z").getAsInt()))continue;
            capacity+=c.has("slotCount")?c.get("slotCount").getAsLong():c.has("capacity")?c.get("capacity").getAsLong():0;
            empty&=c.getAsJsonObject("items").isEmpty()&&c.getAsJsonArray("shulkers").isEmpty()&&!TaskWire.flag(c,"inferred")
                &&(TaskWire.text(c,"status").equals("observed")||TaskWire.text(c,"reason").equals("Storage discovered; contents not yet observed"));
        }
        if(capacity==0||!fills.has(key)&&!empty)return;
        JsonObject row=fills.has(key)?fills.getAsJsonObject(key):new JsonObject();
        if(!row.has("kits")){row.addProperty("kits",0);row.addProperty("uncertain",false);row.addProperty("since",System.currentTimeMillis());row.add("column",column.deepCopy());}
        if(!column.equals(row.get("column"))||row.has("capacity")&&capacity<row.get("capacity").getAsLong())row.addProperty("uncertain",true);
        row.add("column",column.deepCopy());
        row.addProperty("capacity",capacity);fills.add(key,row);db.add("columnFill",fills);
    }

    static void observed(JsonObject db, JsonObject plan, JsonObject observation) {
        if(db.has("columnFill")&&(TaskWire.text(observation,"status").equals("removed")||TaskWire.text(observation,"status").equals("observed")&&!Set.of("StashDeposit","StashResupply").contains(TaskWire.text(plan,"type"))))
            for(JsonElement entry:db.getAsJsonObject("columnFill").asMap().values()){
                JsonObject row=entry.getAsJsonObject();if(StashCatalog.columnContains(row.getAsJsonObject("column"),observation.get("x").getAsInt(),observation.get("y").getAsInt(),observation.get("z").getAsInt()))row.addProperty("uncertain",true);
            }
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
