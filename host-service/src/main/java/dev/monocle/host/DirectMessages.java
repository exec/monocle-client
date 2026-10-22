package dev.monocle.host;

import dev.monocle.coordinator.BotChat;
import java.util.*;
import java.util.function.Predicate;

/** Session-only DM queue; never replays an old world or an expired reply. */
final class DirectMessages {
    record Message(UUID worker,String crew,String scope,String id,String text,long at) { }
    private final Deque<Message> queue=new ArrayDeque<>();
    private final Map<UUID,Long> sentAt=new HashMap<>();
    static boolean isDm(String text){return text.matches("(?is)^/(?:msg|tell|w|whisper)\\s.+");}
    boolean add(UUID worker,String crew,String scope,String id,String text,long now){
        BotChat.command(text);if(!isDm(text))throw new IllegalArgumentException("Expected a whisper command");
        queue.removeIf(m->now-m.at()>120_000);
        if(queue.stream().anyMatch(m->m.worker().equals(worker)&&m.crew().equals(crew)&&m.scope().equals(scope)&&m.text().equals(text)))return true;
        if(queue.size()>=128||queue.stream().filter(m->m.worker().equals(worker)).count()>=16)return false;
        queue.addLast(new Message(worker,crew,scope,id,text,now));return true;
    }
    void sent(UUID worker,long now){sentAt.put(worker,now);}
    void drain(UUID worker,String crew,String scope,long now,Predicate<Message> send){
        queue.removeIf(m->now-m.at()>120_000||m.worker().equals(worker)&&(!m.crew().equals(crew)||!m.scope().equals(scope)));
        if(sentAt.containsKey(worker)&&now-sentAt.get(worker)<5000)return;
        for(var it=queue.iterator();it.hasNext();){Message m=it.next();if(m.worker().equals(worker)){if(send.test(m)){it.remove();sent(worker,now);}return;}}
    }
}
