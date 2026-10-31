package dev.monocle.client.systems.bots;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.BlockItem;

/** Vanilla registry data only; no world, player, runtime catalog or location is read. */
public final class KitBlockItemsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        List<String> blocks=BuiltInRegistries.ITEM.stream().filter(item->item instanceof BlockItem).map(item->BuiltInRegistries.ITEM.getKey(item).toString()).sorted().toList();
        JsonArray ids=new JsonArray();blocks.forEach(ids::add);JsonObject expected=new JsonObject();expected.addProperty("minecraftVersion",SharedConstants.getCurrentVersion().name());expected.add("blockItems",ids);
        Path path=Path.of("host-service/src/main/resources/minecraft-block-items.json");
        if(args.length==1&&args[0].equals("--generate")){Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(expected)+"\n");return;}
        if(!JsonParser.parseString(Files.readString(path)).equals(expected))throw new AssertionError("Regenerate host block-item data with kitBlockItemsCheck --args=--generate after updating Minecraft");
        System.out.println("Host block-item data matches the Minecraft registry ("+blocks.size()+" item types).");
    }
}
