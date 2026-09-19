package dev.monocle.client.gui.screens;

import com.google.gson.*;
import dev.monocle.client.gui.*;
import dev.monocle.client.gui.widgets.*;
import dev.monocle.client.gui.widgets.containers.*;
import dev.monocle.client.systems.modules.world.*;
import dev.monocle.client.utils.render.color.Color;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.time.*;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Catalog-first stash workspace built entirely from the existing Meteor/Monocle widget toolkit. */
public final class StashManagerScreen extends WindowScreen {
    private final StashManager manager;
    private WLabel status,summary;
    private WVerticalList catalog;
    public StashManagerScreen(GuiTheme theme,StashManager manager){super(theme,"Stash Control Center");this.manager=manager;}
    @Override public void initWidgets(){
        status=add(theme.label(manager.status(),true,700)).expandX().widget();
        summary=add(theme.label("",700)).expandX().widget();
        WHorizontalList actions=add(theme.horizontalList()).expandX().widget();
        actions.add(theme.button("Select cuboid · Wooden Pickaxe")).expandX().widget().action=()->dev.monocle.client.systems.modules.Modules.get().get(SchematicSelector.class).equipWand();
        actions.add(theme.button("Save / sync definition")).expandX().widget().action=()->run(manager::exportSelection);
        actions.add(theme.button(manager.isScanning()?"Stop scan":"Scan now")).expandX().widget().action=()->run(manager.isScanning()?manager::stopScan:manager::startScan);
        add(theme.horizontalSeparator()).expandX();
        WHorizontalList body=add(theme.horizontalList()).expandX().widget();
        WVerticalList setup=body.add(theme.verticalList()).top().minWidth(310).widget();
        setup.add(theme.horizontalSeparator("Definition & route")).expandX();
        setup.add(theme.label("The cuboid identifies storage. Home routing is saved per worker by the host, so every bot may use its own /home name.",300).color(theme.textSecondaryColor()));
        setup.add(theme.settings(manager.settings)).expandX();
        setup.add(theme.horizontalSeparator("Workflow")).expandX();
        setup.add(theme.label("Save first, then scan locally or assign Inspect stash from Workers. Host scans automatically substitute each selected worker's saved home route.",300));
        catalog=body.add(theme.verticalList()).top().expandX().widget();rebuild();
        add(theme.horizontalSeparator()).expandX();
        WHorizontalList footer=add(theme.horizontalList()).expandX().widget();
        footer.add(theme.button("Refresh catalog")).widget().action=this::rebuild;
        footer.add(theme.label("Observed is authoritative · inferred is estimated · unscanned is unknown")).expandCellX().right().widget().color(theme.textSecondaryColor());
    }
    private void run(Runnable action){try{action.run();rebuild();status.set(manager.status());}catch(RuntimeException e){status.set(e.getMessage());status.color(new Color(230,95,90));}}
    private void rebuild(){
        catalog.clear();JsonArray values=manager.catalog();long obsidian=0;int observed=0,inferred=0,unscanned=0;
        List<JsonObject> rows=new ArrayList<>();for(JsonElement value:values){JsonObject s=value.getAsJsonObject();rows.add(s);obsidian+=count(s,"minecraft:obsidian");observed+=number(s,"observed");inferred+=number(s,"inferred");unscanned+=number(s,"unscanned");}
        summary.set(rows.size()+" catalog entries · "+format(obsidian)+" obsidian indexed · "+observed+" observed / "+inferred+" inferred / "+unscanned+" unscanned");
        rows.sort(Comparator.comparingLong((JsonObject s)->s.has("updatedAt")?s.get("updatedAt").getAsLong():0).reversed());
        for(JsonObject stash:rows)card(stash);if(rows.isEmpty())catalog.add(theme.label("No stashes yet. Select two corners, name the stash, then Save / sync definition.",360));
    }
    private void card(JsonObject s){
        String name=text(s,"name"),scope=text(s,"scope"),crew=text(s,"crew");WSection card=catalog.add(theme.section(name+"  ·  "+(crew.equals("Local")?"LOCAL":"HOST · "+crew),false)).expandX().widget();
        card.add(theme.label(scope.replace("\n"," · "),390).color(theme.textSecondaryColor()));
        JsonObject b=s.has("bounds")?s.getAsJsonObject("bounds"):new JsonObject();if(b.has("minX"))card.add(theme.label("X "+b.get("minX")+" → "+b.get("maxX")+"   Y "+b.get("minY")+" → "+b.get("maxY")+"   Z "+b.get("minZ")+" → "+b.get("maxZ"),390));
        WHorizontalList health=card.add(theme.horizontalList()).expandX().widget();health.add(theme.label(number(s,"observed")+" observed").color(new Color(100,220,145)));health.add(theme.label(number(s,"inferred")+" inferred").color(new Color(220,183,90)));health.add(theme.label(number(s,"unscanned")+" unscanned").color(number(s,"unscanned")>0?new Color(230,95,90):theme.textSecondaryColor()));
        if(s.has("homes")){WSection routes=card.add(theme.section("Worker routes",false)).expandX().widget();for(var route:s.getAsJsonObject("homes").entrySet()){JsonObject h=route.getValue().getAsJsonObject();routes.add(theme.label(shortId(route.getKey())+"  /home "+text(h,"name")+"  ·  "+number(h,"warmupTicks")/20+"s warmup  ·  "+number(h,"cooldownTicks")/1200+"m cooldown",380));}}
        JsonObject items=s.has("items")?s.getAsJsonObject("items"):new JsonObject();items.entrySet().stream().sorted((a,b2)->Long.compare(b2.getValue().getAsLong(),a.getValue().getAsLong())).limit(6).forEach(e->{var item=BuiltInRegistries.ITEM.getValue(Identifier.parse(e.getKey()));WHorizontalList line=card.add(theme.horizontalList()).expandX().widget();if(item!=null)line.add(theme.item(new ItemStack(item)));line.add(theme.label(e.getKey().replace("minecraft:","")+"  ×  "+format(e.getValue().getAsLong()))).expandCellX();});
        if(s.has("updatedAt"))card.add(theme.label("Updated "+Instant.ofEpochMilli(s.get("updatedAt").getAsLong()).atZone(ZoneId.systemDefault()).toLocalDateTime(),390).color(theme.textSecondaryColor()));
        WHorizontalList edit=card.add(theme.horizontalList()).expandX().widget();edit.add(theme.button("Edit definition")).expandX().widget().action=()->{manager.editDefinition(s,false);mc.gui.setScreen(new StashManagerScreen(theme,manager));};edit.add(theme.button("Reselect corners")).expandX().widget().action=()->manager.editDefinition(s,true);
    }
    private static String text(JsonObject o,String key){return o.has(key)?o.get(key).getAsString():"";}private static int number(JsonObject o,String key){return o.has(key)?o.get(key).getAsInt():0;}private static long count(JsonObject o,String key){return o.has("items")&&o.getAsJsonObject("items").has(key)?o.getAsJsonObject("items").get(key).getAsLong():0;}private static String format(long n){return String.format(Locale.ROOT,"%,d",n);}private static String shortId(String id){return id.length()>8?id.substring(0,8):id;}
    @Override public void tick(){super.tick();status.set(manager.status());}
}
