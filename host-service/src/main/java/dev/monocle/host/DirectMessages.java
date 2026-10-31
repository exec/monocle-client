package dev.monocle.host;

import dev.monocle.coordinator.BotChat;
import java.util.*;
import java.util.function.Predicate;

/** Session-only DM queue; whispers survive dimension changes, never another server or an expired reply. */
final class DirectMessages {
    record Message(UUID worker,String crew,String scope,String id,String text,long at,String cooldownRecipient,long cooldownUntil,boolean creditCooldown) {
        Message(UUID worker,String crew,String scope,String id,String text,long at,String cooldownRecipient,long cooldownUntil){this(worker,crew,scope,id,text,at,cooldownRecipient,cooldownUntil,false);}
        Message(UUID worker,String crew,String scope,String id,String text,long at){this(worker,crew,scope,id,text,at,"",0);}
        String text(long now){return cooldownRecipient.isEmpty()?text:MikeKitHandler.whisper(cooldownRecipient,KitBot.cooldownText(cooldownUntil-now));}
    }
    private final Deque<Message> queue=new ArrayDeque<>();
    private final Map<UUID,Long> sentAt=new HashMap<>();
    static boolean isDm(String text){return text.matches("(?is)^/(?:msg|tell|w|whisper)\\s.+");}
    boolean add(UUID worker,String crew,String scope,String id,String text,long now){
        return addAll(List.of(new Message(worker,crew,scope,id,text,now)),now);
    }
    boolean cooldown(UUID worker,String crew,String scope,String recipient,long until,long now){return cooldown(worker,crew,scope,recipient,until,now,false);}
    boolean cooldown(UUID worker,String crew,String scope,String recipient,long until,long now,boolean paid){
        return addAll(List.of(new Message(worker,crew,scope,UUID.randomUUID().toString(),MikeKitHandler.whisper(recipient,KitBot.cooldownText(until-now)),now,recipient,until,paid)),now);
    }
    boolean addAll(List<Message> messages,long now){
        for(Message m:messages){BotChat.command(m.text());if(!isDm(m.text()))throw new IllegalArgumentException("Expected a whisper command");}
        queue.removeIf(m->now-m.at()>120_000);List<Message> added=new ArrayList<>();
        for(Message m:messages)if(java.util.stream.Stream.concat(queue.stream(),added.stream()).noneMatch(existing->
            existing.worker().equals(m.worker())&&existing.crew().equals(m.crew())&&existing.scope().equals(m.scope())
            &&(existing.id().equals(m.id())||messages.size()==1&&(existing.text().equals(m.text())
                ||!m.cooldownRecipient().isEmpty()&&existing.cooldownRecipient().equalsIgnoreCase(m.cooldownRecipient())&&existing.creditCooldown()==m.creditCooldown()))))added.add(m);
        if(queue.size()+added.size()>128)return false;
        for(Message m:added)if(java.util.stream.Stream.concat(queue.stream(),added.stream()).filter(existing->existing.worker().equals(m.worker())).count()>16)return false;
        queue.addAll(added);return true;
    }
    void sent(UUID worker,long now){sentAt.put(worker,now);}
    void drain(UUID worker,String crew,String scope,long now,Predicate<Message> send){
        queue.removeIf(m->now-m.at()>120_000||m.worker().equals(worker)&&(!m.crew().equals(crew)||!BotChat.commandScope(m.text(),m.scope(),scope)));
        if(sentAt.containsKey(worker)&&now-sentAt.get(worker)<5000)return;
        for(var it=queue.iterator();it.hasNext();){Message m=it.next();if(m.worker().equals(worker)){if(send.test(m)){it.remove();sent(worker,now);}return;}}
    }
}
