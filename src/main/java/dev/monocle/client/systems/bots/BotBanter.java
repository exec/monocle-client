package dev.monocle.client.systems.bots;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Local, witnessed supply contention only. No arbitrary host-supplied public-chat command. */
public final class BotBanter {
    private static final BotBanter INSTANCE = new BotBanter();
    private final Map<UUID, Incident> pending = new LinkedHashMap<>();
    private final Set<UUID> seen = new LinkedHashSet<>();
    private long nextMessage;
    private boolean listening;
    private record Incident(UUID player, Object world, Map<UUID,Integer> amounts, long quietAfter) {}

    public static void pickup(UUID drop, UUID collector, int amount, Set<UUID> crew) {
        if (!Utils.canUpdate() || !Config.get().banterMode.get() || amount <= 0 || !crew.contains(collector)) return;
        INSTANCE.observe(drop,collector,amount);
    }
    private void observe(UUID drop, UUID collector, int amount) {
        if (seen.contains(drop)) return;
        Incident old=pending.get(drop);
        Map<UUID,Integer> totals=old==null?new HashMap<>():new HashMap<>(old.amounts);
        totals.merge(collector,amount,Integer::sum);
        if(pending.size()>=32 && old==null) return;
        pending.put(drop,new Incident(mc.player.getUUID(),mc.level,totals,System.nanoTime()+2_000_000_000L));
        if(!listening) { MonocleClient.EVENT_BUS.subscribe(this); listening=true; }
    }
    @EventHandler private void tick(TickEvent.Post event) {
        long now=System.nanoTime();
        for(var it=pending.entrySet().iterator();it.hasNext();) {
            var entry=it.next(); Incident incident=entry.getValue(); if(now<incident.quietAfter) continue;
            it.remove(); seen.add(entry.getKey());
            // Bounded per-session deduplication; server item UUIDs identify incidents, not player names.
            if(seen.size()>256) seen.remove(seen.iterator().next());
            if(!Utils.canUpdate() || incident.world!=mc.level || !incident.player.equals(mc.player.getUUID()) || !Config.get().banterMode.get() || now<nextMessage) continue;
            UUID winner=winner(incident.player,incident.amounts);
            var player=winner==null?null:mc.level.getPlayerByUUID(winner);
            if(player==null) continue;
            String name=player.getGameProfile().name();
            if(!name.matches("[A-Za-z0-9_]{1,16}")) continue;
            nextMessage=now+30_000_000_000L;
            mc.player.connection.sendChat(name+" Fuck you!"); // Deliberately bypass .chat IRC routing.
        }
        if(pending.isEmpty()) { MonocleClient.EVENT_BUS.unsubscribe(this); listening=false; }
    }
    static UUID winner(UUID local, Map<UUID,Integer> amounts) {
        int mine=amounts.getOrDefault(local,0);
        return amounts.entrySet().stream().filter(e -> !e.getKey().equals(local) && e.getValue()>mine)
            .sorted(Map.Entry.<UUID,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }
}
