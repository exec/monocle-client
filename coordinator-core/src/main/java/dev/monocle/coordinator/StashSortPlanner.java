package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

/** A snapshot proposal only. No reservations, journal writes, container clicks or task submissions. */
public final class StashSortPlanner {
    private StashSortPlanner() { }
    private static final List<String> ORDER=List.of("obsidian","crying-obsidian","building","stone","wood","concrete","wool","nature","grass-block","redstone","storage","shulkers","tools","elytra","armor","netherite-helmet","netherite-chestplate","netherite-leggings","netherite-boots","gapples","xp","totems","pvp","grief","valuables","trims");
    private record Pos(int x,int y,int z) {
        static Pos of(JsonObject o){return new Pos(o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt());}
        String key(){return x+","+y+","+z;}
        String horizontal(){return x+","+z;}
        JsonObject json(){JsonObject o=new JsonObject();o.addProperty("x",x);o.addProperty("y",y);o.addProperty("z",z);return o;}
    }
    private record Contents(String kit,Set<String> items) { }
    private static final class Column {
        final Pos anchor;final List<Pos> feet;final String axis;final Map<String,Long> stock=new TreeMap<>();final Set<String> reasons=new TreeSet<>();final Set<Integer> rows=new TreeSet<>();
        final Set<String> containers=new LinkedHashSet<>();
        long capacity,kept,planned;int occupied,hopperSlots;String target="empty";boolean growth;
        Column(Pos anchor,List<Pos> feet,String axis){this.anchor=anchor;this.feet=feet;this.axis=axis;}
        long kits(){return stock.values().stream().mapToLong(Long::longValue).sum();}
        boolean pinned(){return !reasons.isEmpty();}
        boolean empty(){return !pinned()&&occupied==0&&kits()==0;}
        JsonObject coordinates(){JsonObject o=anchor.json();o.addProperty("axis",axis);return o;}
    }
    private static boolean chest(JsonObject o){return Set.of("minecraft:chest","minecraft:trapped_chest","minecraft:barrel").contains(text(o,"block"));}
    private static List<Pos> parts(JsonObject o){
        if(!o.has("parts")||o.getAsJsonArray("parts").isEmpty())return List.of(Pos.of(o));
        return o.getAsJsonArray("parts").asList().stream().map(v->Pos.of(v.getAsJsonObject())).distinct().sorted(Comparator.comparing(Pos::key)).toList();
    }
    private static long at(JsonObject o){return o.has("observedAt")?o.get("observedAt").getAsLong():0;}
    private static int capacity(JsonObject o){return o.has("slotCount")?o.get("slotCount").getAsInt():o.has("capacity")?o.get("capacity").getAsInt():0;}
    private static String bucket(Pos p,String axis){return switch(axis){case "X"->Integer.toString(p.x);case "Z"->Integer.toString(p.z);default->p.horizontal();};}
    private static boolean belongs(Column c,JsonObject record){
        for(Pos foot:c.feet)for(Pos p:parts(record)){
            int dx=p.x-foot.x,dy=p.y-foot.y,dz=p.z-foot.z;
            if(dy>=0&&switch(c.axis){case "X"->dx==0&&(dz==0||Math.abs(dz)==dy);case "Z"->dz==0&&(dx==0||Math.abs(dx)==dy);default->dx==0&&dz==0;})return true;
        }
        return false;
    }
    private static JsonObject normalizedCategory(JsonObject db,JsonObject source){
        JsonObject c=source.deepCopy();if(text(c,"mode").equals("Kits")){
            JsonArray members=new JsonArray();for(JsonElement id:c.getAsJsonArray("members"))members.add(KitTypes.canonical(db,id.getAsString()));c.add("members",members);
        }return c;
    }
    private static void observe(Column c,JsonObject o,Map<String,String> kitGroups,Map<String,JsonObject> categories,JsonObject db,Set<String> unresolved,Map<String,Set<Column>> owners,Map<Contents,List<String>> classifications){
        c.containers.add(Pos.of(o).key());
        if(!text(o,"status").equals("observed")||TaskWire.flag(o,"inferred")){c.reasons.add("Unknown or inferred container contents");return;}
        if(!o.has("occupiedSlots")||capacity(o)<=0){c.reasons.add("Exact slot occupancy/capacity is missing");return;}
        if(chest(o)&&capacity(o)>27&&parts(o).size()!=2)c.reasons.add("Double chest footprint is missing");
        if(o.has("capacity")&&o.has("slotCount")&&!o.get("capacity").equals(o.get("slotCount")))c.reasons.add("Container size disagrees with mapped geometry");
        int occupied=o.get("occupiedSlots").getAsInt();if(chest(o))c.occupied+=occupied;
        Set<Integer> slots=new HashSet<>();
        for(JsonElement value:o.getAsJsonArray("shulkers")){
            JsonObject box=value.getAsJsonObject();String kit=KitTypes.canonical(db,StashCatalog.kitTypeId(box));long count=box.get("quantity").getAsLong();
            if(count<1)continue;
            owners.computeIfAbsent(kit,k->new LinkedHashSet<>()).add(c);
            if(!box.has("slot")||!slots.add(box.get("slot").getAsInt())){c.reasons.add("Shulker slot evidence is incomplete or duplicated");continue;}
            JsonObject contents=box.getAsJsonObject("items");List<String> matches=List.of();
            if(contents!=null&&!contents.isEmpty()&&TaskWire.flag(box,"contentsKnown")&&db.getAsJsonObject("kitTypes").has(kit)){
                if(contents.entrySet().stream().allMatch(e->e.getValue().getAsLong()>0))matches=classifications.computeIfAbsent(new Contents(kit,Set.copyOf(contents.keySet())),k->{
                    List<String> ids=new ArrayList<>();for(var category:categories.entrySet())if(KitCategories.matches(category.getValue(),kit,contents))ids.add(category.getKey());return List.copyOf(ids);
                });
            }
            String group=matches.size()==1?matches.getFirst():"unclassified:"+kit;
            if(matches.size()!=1){c.reasons.add(matches.isEmpty()?"Kit needs a category or known contents":"Kit matches multiple categories");unresolved.add(kit);}
            String prior=kitGroups.putIfAbsent(kit,group);
            if(prior!=null&&!prior.equals(group)){c.reasons.add("Kit variants disagree on category");unresolved.add(kit);}
            c.stock.merge(group,count,Long::sum);
        }
        if(occupied!=slots.size())c.reasons.add("Loose items or unknown occupied slots");
    }
    public static JsonObject preview(JsonObject snapshot,JsonObject categoryCatalog,int buffers,int carrySlots,List<String> requestedOrder){
        return preview(snapshot,categoryCatalog,buffers,carrySlots,requestedOrder,null);
    }
    public static JsonObject preview(JsonObject snapshot,JsonObject categoryCatalog,int buffers,int carrySlots,List<String> requestedOrder,List<String> selectedCategories){
        return preview(snapshot,categoryCatalog,buffers,carrySlots,requestedOrder,selectedCategories,0);
    }
    public static JsonObject preview(JsonObject snapshot,JsonObject categoryCatalog,int buffers,int carrySlots,List<String> requestedOrder,List<String> selectedCategories,int growthColumns){
        if(growthColumns<0||growthColumns>2)throw new IllegalArgumentException("Choose 0–2 growth columns between categories");
        if(buffers<1||buffers>16||carrySlots<1||carrySlots>36)throw new IllegalArgumentException("Choose 1–16 buffer columns and 1–36 carried kit slots");
        if(snapshot==null||snapshot.isEmpty())throw new IllegalArgumentException("Unknown stash");
        JsonObject db=snapshot.deepCopy();Map<String,JsonObject> categories=new TreeMap<>();
        for(var e:categoryCatalog.getAsJsonObject("categories").entrySet()){
            JsonObject rule=KitCategories.checked(e.getValue().getAsJsonObject());if(KitCategories.usedFor(rule,"Storage"))categories.put(e.getKey(),normalizedCategory(db,rule));
        }
        JsonObject definitions=new JsonObject();categories.forEach((id,rule)->definitions.add(id,rule.deepCopy()));
        if(selectedCategories!=null){
            if(selectedCategories.size()>KitCategories.MAX_CATEGORIES||new HashSet<>(selectedCategories).size()!=selectedCategories.size()||!categories.keySet().containsAll(selectedCategories))throw new IllegalArgumentException("Select distinct known storage category IDs");
            categories.keySet().retainAll(new HashSet<>(selectedCategories));
        }
        if(requestedOrder.size()>KitCategories.MAX_CATEGORIES||new HashSet<>(requestedOrder).size()!=requestedOrder.size()||!categories.keySet().containsAll(requestedOrder))throw new IllegalArgumentException("Category order must contain distinct known category IDs");
        Map<String,JsonObject> physical=new TreeMap<>();int duplicateRecords=0;
        for(JsonElement value:db.getAsJsonObject("containers").asMap().values()){
            JsonObject o=value.getAsJsonObject();if(text(o,"status").equals("removed"))continue;
            if(!chest(o)&&!text(o,"block").equals("minecraft:hopper"))continue;
            String key=parts(o).stream().map(Pos::key).reduce((a,b)->a+";"+b).orElseThrow();
            JsonObject previous=physical.get(key);if(previous!=null)duplicateRecords++;
            if(previous==null||at(o)>at(previous))physical.put(key,o);
        }
        List<JsonObject> chests=physical.values().stream().filter(StashSortPlanner::chest).toList();
        if(chests.isEmpty())throw new IllegalArgumentException("Map and observe the stash before previewing a sort");
        int bottom=chests.stream().mapToInt(o->Pos.of(o).y).min().orElseThrow();
        List<JsonObject> anchors=chests.stream().filter(o->Pos.of(o).y==bottom).sorted(Comparator.comparingInt((JsonObject o)->Pos.of(o).x).thenComparingInt(o->Pos.of(o).z)).toList();
        if(anchors.size()>2048)throw new IllegalArgumentException("Sorting preview supports up to 2048 bottom columns");
        long xs=anchors.stream().map(o->Pos.of(o).x).distinct().count(),zs=anchors.stream().map(o->Pos.of(o).z).distinct().count();String axis=zs==1&&xs>1?"X":xs==1&&zs>1?"Z":"Vertical";
        List<Column> columns=new ArrayList<>();Map<String,Set<Column>> buckets=new HashMap<>();
        for(JsonObject o:anchors){Column c=new Column(Pos.of(o),parts(o),axis);if(xs>1&&zs>1)c.reasons.add("Multiple-bank geometry needs explicit layout support");columns.add(c);for(Pos p:c.feet)buckets.computeIfAbsent(bucket(p,axis),k->new LinkedHashSet<>()).add(c);}
        Map<String,Column> chestParts=new HashMap<>();Set<String> unresolved=new TreeSet<>();Map<String,String> kitGroups=new TreeMap<>();Map<String,Set<Column>> owners=new HashMap<>();Map<Contents,List<String>> classifications=new HashMap<>();int unmapped=0;long unmappedKits=0;
        for(JsonObject o:chests){
            Set<Column> candidates=new LinkedHashSet<>();for(Pos p:parts(o))candidates.addAll(buckets.getOrDefault(bucket(p,axis),Set.of()));candidates.removeIf(c->!belongs(c,o));
            if(candidates.size()!=1){unmapped++;unmappedKits+=boxCount(o);for(Column c:candidates)c.reasons.add("Ambiguous column geometry");continue;}
            Column c=candidates.iterator().next();c.capacity+=capacity(o);if(!c.rows.add(Pos.of(o).y))c.reasons.add("Multiple chests overlap one column row");
            for(Pos p:parts(o)){Column prior=chestParts.putIfAbsent(p.key(),c);if(prior!=null&&prior!=c){prior.reasons.add("Overlapping chest halves");c.reasons.add("Overlapping chest halves");}}
            observe(c,o,kitGroups,categories,db,unresolved,owners,classifications);
        }
        for(JsonObject o:physical.values())if(text(o,"block").equals("minecraft:hopper")){
            Pos p=Pos.of(o);Column c=chestParts.get(new Pos(p.x,p.y+1,p.z).key());
            if(c==null){
                Set<Column> outlets=new LinkedHashSet<>();for(int[] delta:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})){
                    Column next=chestParts.get(new Pos(p.x+delta[0],p.y,p.z+delta[1]).key());if(next!=null)outlets.add(next);
                }
                if(outlets.size()==1)c=outlets.iterator().next();
            }
            if(c==null){unmapped++;unmappedKits+=boxCount(o);continue;}
            c.hopperSlots+=capacity(o);observe(c,o,kitGroups,categories,db,unresolved,owners,classifications);
        }
        for(Column c:columns){
            if(c.capacity<=0)c.reasons.add("Column has no verified capacity");
            if(!c.rows.isEmpty()&&c.rows.size()!=Collections.max(c.rows)-bottom+1)c.reasons.add("Missing chest observations inside column");
            if(c.stock.keySet().stream().anyMatch(k->k.startsWith("unclassified:")))c.reasons.add("Unclassified contents are preserved in place");
        }
        // A category disagreement in one box must pin every column containing that kit.
        for(String kit:unresolved)for(Column c:owners.getOrDefault(kit,Set.of()))c.reasons.add("Unclassified contents are preserved in place");
        List<Column> empty=columns.stream().filter(Column::empty).toList();
        Set<Column> staging=new LinkedHashSet<>();for(int i=empty.size()-1;i>=0&&staging.size()<buffers;i--)staging.add(empty.get(i));
        for(Column c:columns)if(c.pinned())c.target="preserve";else if(staging.contains(c))c.target="buffer";
        Map<String,Long> demand=new TreeMap<>();for(Column c:columns)if(!c.pinned())c.stock.forEach((k,n)->demand.merge(k,n,Long::sum));
        List<String> order=new ArrayList<>(demand.keySet());order.sort(Comparator.comparingInt((String id)->rank(id,categories,requestedOrder)).thenComparing(id->text(categories.get(id),"name")).thenComparing(id->id));
        List<Column> available=columns.stream().filter(c->!c.pinned()&&!staging.contains(c)).toList();int cursor=0;long shortfall=0;
        JsonArray groups=new JsonArray();
        int growthShortfall=0,growthReserved=0;
        for(String group:order){
            long need=demand.get(group),capacity=0;List<Column> selected=new ArrayList<>();
            while(capacity<need&&cursor<available.size()){Column c=available.get(cursor++);c.target=group;selected.add(c);capacity+=c.capacity;}
            // Preserved columns may split a block; its growth gap must still be physically adjacent to its final column.
            if(growthColumns>0&&!group.equals(order.getLast())){
                while(!selected.isEmpty()&&cursor<available.size()&&columns.indexOf(available.get(cursor))!=columns.indexOf(selected.getLast())+1){Column c=available.get(cursor++);c.target=group;selected.add(c);capacity+=c.capacity;}
            }
            long retained=0;for(Column c:selected){c.kept=Math.min(c.capacity,c.stock.getOrDefault(group,0L));c.planned=c.kept;retained+=c.kept;}
            long left=need-retained;for(Column c:selected){long add=Math.min(left,c.capacity-c.planned);c.planned+=add;left-=add;}
            shortfall+=left;
            int reserved=0;
            if(!group.equals(order.getLast()))for(int gap=0;gap<growthColumns;gap++){
                if(cursor>=available.size()||selected.isEmpty()||columns.indexOf(available.get(cursor))!=columns.indexOf(selected.getLast())+1+gap){growthShortfall+=growthColumns-gap;break;}
                Column c=available.get(cursor++);c.target=group;c.growth=true;reserved++;growthReserved++;
            }
            JsonObject row=new JsonObject();row.addProperty("categoryId",group);row.addProperty("name",text(categories.get(group),"name"));row.addProperty("kits",need);row.addProperty("columns",selected.size());row.addProperty("capacity",capacity);row.addProperty("keptInPlace",retained);row.addProperty("kitsToMove",need-retained);row.addProperty("capacityShortfall",left);groups.add(row);
            row.addProperty("growthColumns",reserved);
        }
        JsonArray moves=new JsonArray();long moveKits=0,pairLoads=0;int moveCount=0;Map<Column,Set<Column>> dependencies=new LinkedHashMap<>();
        if(shortfall==0&&growthShortfall==0&&staging.size()==buffers)for(String group:order){
            Map<Column,Long> supply=new LinkedHashMap<>();for(Column c:available){long n=c.stock.getOrDefault(group,0L)-(c.target.equals(group)?c.kept:0);if(n>0)supply.put(c,n);}
            for(Column dest:available)if(dest.target.equals(group)&&!dest.growth){
                long need=dest.planned-dest.kept;
                List<Column> nearest=new ArrayList<>(supply.keySet());nearest.sort(Comparator.comparingLong((Column c)->distance(c,dest)).thenComparing(c->c.anchor.key()));
                for(Column source:nearest){long n=Math.min(need,supply.get(source));if(n==0)continue;supply.put(source,supply.get(source)-n);need-=n;moveKits+=n;pairLoads+=(n+carrySlots-1)/carrySlots;moveCount++;
                    boolean clear=dest.stock.entrySet().stream().anyMatch(e->!e.getKey().equals(group)&&e.getValue()>0);
                    if(clear)dependencies.computeIfAbsent(source,k->new LinkedHashSet<>()).add(dest);
                    if(moves.size()<2048){JsonObject move=new JsonObject();move.addProperty("categoryId",group);move.add("from",source.coordinates());move.add("to",dest.coordinates());move.addProperty("kits",n);move.addProperty("requiresDestinationClearance",clear);moves.add(move);}
                }
                if(need!=0)throw new IllegalStateException("Sort proposal failed stock conservation");
            }
            if(supply.values().stream().anyMatch(n->n!=0))throw new IllegalStateException("Sort proposal left unmatched stock");
        }
        int pinned=(int)columns.stream().filter(Column::pinned).count(),futureEmpty=(int)columns.stream().filter(c->!c.pinned()&&c.planned==0).count();
        long stagingSlots=staging.stream().mapToLong(c->c.capacity).sum(),largest=available.stream().mapToLong(Column::kits).max().orElse(0),kept=columns.stream().mapToLong(c->c.kept).sum();
        JsonObject totals=new JsonObject();totals.addProperty("columns",columns.size());totals.addProperty("emptyColumns",empty.size());totals.addProperty("pinnedColumns",pinned);totals.addProperty("bufferColumnsRequested",buffers);totals.addProperty("bufferColumnsReserved",staging.size());totals.addProperty("bufferSlots",stagingSlots);totals.addProperty("largestMovableColumnKits",largest);totals.addProperty("canStageLargestColumn",stagingSlots>=largest);totals.addProperty("projectedEmptyColumns",futureEmpty);totals.addProperty("netColumnsFreed",futureEmpty-empty.size());
        totals.addProperty("growthColumnsReserved",growthReserved);totals.addProperty("growthColumnsShortfall",growthShortfall);totals.addProperty("unassignedEmptyColumns",columns.stream().filter(c->c.target.equals("empty")).count());
        totals.addProperty("additionalCategoryOwnedEmptyColumns",columns.stream().filter(c->!c.growth&&categories.containsKey(c.target)&&c.planned==0).count());
        long staged=0;int count=0;for(long capacity:empty.stream().mapToLong(c->c.capacity).boxed().sorted(Comparator.reverseOrder()).toList()){if(staged>=largest)break;staged+=capacity;count++;}
        totals.add("minimumBuffersForWholeColumn",staged>=largest?new JsonPrimitive(count):JsonNull.INSTANCE);
        totals.addProperty("chestSlots",columns.stream().mapToLong(c->c.capacity).sum());totals.addProperty("hopperSlotsExcludedFromDestinationCapacity",columns.stream().mapToLong(c->c.hopperSlots).sum());totals.addProperty("movableKits",demand.values().stream().mapToLong(Long::longValue).sum());totals.addProperty("pinnedKits",columns.stream().filter(Column::pinned).mapToLong(Column::kits).sum());totals.addProperty("keptInPlace",kept);totals.addProperty("kitsToMove",shortfall==0?moveKits:0);totals.addProperty("minimumInventoryLoads",(moveKits+carrySlots-1)/carrySlots);totals.addProperty("pairwiseInventoryLoads",pairLoads);totals.addProperty("capacityShortfall",shortfall);totals.addProperty("unmappedContainers",unmapped);totals.addProperty("unmappedKits",unmappedKits);totals.addProperty("duplicateRecordsIgnored",duplicateRecords);totals.addProperty("cyclicDependencies",hasCycle(dependencies));
        LongSummaryStatistics ages=physical.values().stream().filter(o->text(o,"status").equals("observed")&&!TaskWire.flag(o,"inferred")&&at(o)>0).mapToLong(StashSortPlanner::at).summaryStatistics();
        if(ages.getCount()>0){totals.addProperty("oldestObservationAt",ages.getMin());totals.addProperty("newestObservationAt",ages.getMax());}
        JsonArray columnRows=new JsonArray();for(Column c:columns){JsonObject row=c.coordinates();row.addProperty("index",columnRows.size()+1);row.addProperty("capacity",c.capacity);row.addProperty("currentKits",c.kits());row.add("currentCategories",new Gson().toJsonTree(c.stock));row.addProperty("target",c.target);row.addProperty("plannedKits",c.planned);row.addProperty("keptInPlace",c.kept);row.add("preserveReasons",new Gson().toJsonTree(c.reasons));JsonObject reservation=db.has("columnReservations")?db.getAsJsonObject("columnReservations").getAsJsonObject(c.anchor.horizontal()):null;if(reservation!=null)row.add("currentReservation",reservation.deepCopy());columnRows.add(row);}
        JsonArray warnings=new JsonArray();warnings.add("Read-only proposal: no reservations, items or jobs were changed.");warnings.add("Counts are spatial observations, not an atomic inventory snapshot; verify containers and hopper chains before execution.");warnings.add("Load estimates exclude travel, opening/verification, temporary staging and recovery; they are not an ETA.");warnings.add("Only chest slots count as destination capacity; recorded kits in hoppers still count as stock to relocate.");warnings.add("The proposed order replaces reservations only in this preview; current reservations remain unchanged.");
        for(int i=0;i<columns.size();i++){JsonObject row=columnRows.get(i).getAsJsonObject();row.addProperty("growth",columns.get(i).growth);row.add("containers",new Gson().toJsonTree(columns.get(i).containers));}
        if(growthShortfall>0)warnings.add("The requested physically adjacent growth gaps do not fit; do not execute this layout.");
        if(pinned>0)warnings.add("Unclassified, loose, unknown or ambiguous contents pin entire columns; fix categories/observations before sorting those columns.");
        if(unmapped>0)warnings.add("Unmapped containers are excluded; this is not a complete-stash plan.");
        if(TaskWire.flag(db.has("transferStock")?db.getAsJsonObject("transferStock"):new JsonObject(),"uncertain"))warnings.add("Transfer-adjusted stock is uncertain and is not used to invent item locations.");
        if(staging.size()<buffers)warnings.add("Not enough verified empty columns for the requested buffer reserve.");
        if(shortfall>0)warnings.add("The proposed category layout does not fit in eligible columns with these buffers; no move schedule was produced.");
        if(hasCycle(dependencies))warnings.add("Column dependency cycles require temporary staging; additional buffer trips are not included in load estimates.");
        warnings.add("Category blocks are packed in the chosen order; this is deterministic, not a globally minimum-move solution.");
        JsonObject r=new JsonObject();r.addProperty("version",1);r.addProperty("name",text(db,"name"));r.addProperty("crew",text(db,"crew"));r.addProperty("scope",text(db,"scope"));r.addProperty("stashRevision",StashCatalog.revisionHash(db));r.addProperty("categoryRevision",text(categoryCatalog,"revision"));r.addProperty("executionSupported",true);r.addProperty("feasibleForMovableColumns",shortfall==0&&growthShortfall==0&&staging.size()==buffers);r.addProperty("completeMappedCoverage",pinned==0&&unmapped==0);r.addProperty("carrySlots",carrySlots);r.addProperty("growthColumns",growthColumns);r.add("selectedCategoryIds",new Gson().toJsonTree(categories.keySet()));r.add("categoryDefinitions",definitions);r.add("categoryOrder",new Gson().toJsonTree(order));r.add("summary",totals);r.add("categories",groups);r.add("columns",columnRows);r.add("moves",moves);r.addProperty("moveDetailsTruncated",moveCount>moves.size());r.add("unclassifiedKitTypeIds",new Gson().toJsonTree(unresolved));r.add("warnings",warnings);r.addProperty("planId",TaskFiles.hash(r.toString()));return r;
    }
    private static long boxCount(JsonObject o){return o.getAsJsonArray("shulkers").asList().stream().mapToLong(v->v.getAsJsonObject().get("quantity").getAsLong()).sum();}
    private static int rank(String id,Map<String,JsonObject> categories,List<String> requested){
        int explicit=requested.indexOf(id);if(explicit>=0)return explicit;
        String slug=text(categories.get(id),"slug");if(slug.startsWith("storage-"))slug=slug.substring(8);
        int usual=ORDER.indexOf(slug);return requested.size()+(usual<0?ORDER.size():usual);
    }
    private static long distance(Column a,Column b){return Math.abs((long)a.anchor.x-b.anchor.x)+Math.abs((long)a.anchor.z-b.anchor.z);}
    private static boolean hasCycle(Map<Column,Set<Column>> graph){
        Map<Column,Integer> degree=new HashMap<>();graph.forEach((a,bs)->{degree.putIfAbsent(a,0);for(Column b:bs)degree.merge(b,1,Integer::sum);});
        Deque<Column> ready=new ArrayDeque<>();degree.forEach((c,n)->{if(n==0)ready.add(c);});int removed=0;
        while(!ready.isEmpty()){Column c=ready.remove();removed++;for(Column b:graph.getOrDefault(c,Set.of()))if(degree.merge(b,-1,Integer::sum)==0)ready.add(b);}
        return removed!=degree.size();
    }
}
