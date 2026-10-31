package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.nio.file.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

final class CreditBankCheck {
    private static final String PLAYER="00000000-0000-0000-0000-000000000071",OTHER="00000000-0000-0000-0000-000000000072",WORKER="00000000-0000-0000-0000-000000000073";
    static JsonObject object(String json){return JsonParser.parseString(json).getAsJsonObject();}
    static void rejects(Runnable work){try{work.run();throw new AssertionError("Invalid bank operation accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    static void run()throws Exception{
        Path root=Files.createTempDirectory("synthetic-credit-bank-");String transaction=UUID.randomUUID().toString(),token;
        try(CreditBank bank=new CreditBank(root)){
            assert !bank.account(PLAYER).get("exists").getAsBoolean()&&bank.account(PLAYER).get("balance").getAsLong()==0;
            assert bank.accounts().getAsJsonArray("items").isEmpty()&&bank.history(PLAYER).getAsJsonArray("items").isEmpty();
            rejects(()->bank.adjust(UUID.randomUUID().toString(),PLAYER,"SyntheticUser",-1,"Revocation","test"));
            assert bank.accounts().getAsJsonArray("items").isEmpty() : "Failed debit never creates an account";
            JsonObject receipt=bank.adjust(transaction,PLAYER,"SyntheticUser",10,"Reward","test");assert receipt.get("balance").getAsLong()==10;
            assert bank.adjust(transaction,PLAYER,"SyntheticUser",10,"Reward","test").equals(receipt)&&bank.account(PLAYER).get("balance").getAsLong()==10;
            rejects(()->bank.adjust(transaction,PLAYER,"SyntheticUser",11,"Reward","test"));
            rejects(()->bank.adjust(UUID.randomUUID().toString(),PLAYER,"SyntheticUser",-11,"Revocation","test"));
            rejects(()->CreditBank.amount(object("{amount:1.5}"),"amount",100));rejects(()->CreditBank.amount(object("{amount:'5'}"),"amount",100));
            bank.adjust(UUID.randomUUID().toString(),PLAYER,"SyntheticUser",-10,"Revocation","test");assert bank.account(PLAYER).get("exists").getAsBoolean()&&bank.account(PLAYER).get("balance").getAsLong()==0;
            JsonObject key=bank.createKey(UUID.randomUUID().toString(),"Synthetic integration",Set.of("balances:read"));token=text(key,"token");
            assert bank.authorize("Bearer "+token,"balances:read")!=null&&bank.authorize("Bearer "+token,"balances:write")==null;
            assert !bank.keys().toString().contains(token)&&!bank.createKey(text(key,"id"),"Synthetic integration",Set.of("balances:read")).has("token") : "Secrets are returned only on initial creation";
            bank.revokeKey(text(key,"id"));assert bank.authorize("Bearer "+token,"balances:read")==null;
            for(String suffix:List.of("","-wal")){Path file=root.resolve("kit-credits.sqlite"+suffix);if(Files.exists(file))assert !new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.ISO_8859_1).contains(token) : "API key secrets must never be persisted";}
        }
        try(CreditBank bank=new CreditBank(root)){
            assert bank.account(PLAYER).get("balance").getAsLong()==0&&bank.history(PLAYER).getAsJsonArray("items").size()==2;
            assert !bank.account(OTHER).get("exists").getAsBoolean();
        }
        paidOrders();toolRoles();receipts();
        System.out.println("Credit bank checks passed: lazy accounts, atomic orders, idempotency, refunds, independent allowances, role-bound tools and private keys.");
    }
    private static void paidOrders()throws Exception{
        Path root=Files.createTempDirectory("synthetic-paid-kit-orders-");String scope="synthetic.invalid\nminecraft:the_end";
        // Independently invented synthetic storage geometry.
        JsonObject bounds=StashCatalog.plan(object("{name:'SyntheticDepot',homeName:'synthetic-home',minX:100,maxX:105,minY:90,maxY:90,minZ:0,maxZ:0}"));StashCatalog.define(root,"Default",scope,bounds);
        for(int x=100;x<=105;x++){
            JsonObject row=object("{y:90,z:0,status:'observed',block:'minecraft:chest',items:{'minecraft:blue_shulker_box':50},shulkers:[]}");row.addProperty("x",x);
            for(int slot=0;slot<50;slot++){JsonObject box=object("{item:'minecraft:blue_shulker_box',name:'Synthetic paving',quantity:1,contentsKnown:true,items:{'minecraft:obsidian':1728}}");box.addProperty("slot",slot);row.getAsJsonArray("shulkers").add(box);}StashCatalog.save(root,"Default",scope,bounds,row);
        }
        KitCategories.seed(root,"Default",scope,StashCatalog.get(root,"Default",scope,"SyntheticDepot"),270);
        JsonObject config=object("{enabled:true,publicAccess:true,crew:'Default',source:'SyntheticDepot',minimumStock:270,cooldownSeconds:3600,whitelist:[]}");config.addProperty("scope",scope);config.addProperty("worker",WORKER);
        KitBot legacy=new KitBot(root);legacy.configure(config,text(legacy.view(),"revision"));
        OperationsLibrary library=new OperationsLibrary(root.resolve("operations.json"));
        try(CreditBank bank=new CreditBank(root)){
            KitBot bot=new KitBot(root,bank);assert bot.view().getAsJsonObject("config").equals(config) : "Existing private configuration migrates intact";
            bank.adjust(UUID.randomUUID().toString(),PLAYER,"SyntheticUser",10,"Reward","test");
            JsonObject sender=object("{name:'SyntheticUser'}");sender.addProperty("uuid",PLAYER);bot.observeSender("SyntheticUser whispers: kit obsidian 1",sender);
            JsonObject free=bot.receive("SyntheticUser whispers: kit obsidian 1",1000000,library);assert free.getAsJsonObject("submission").getAsJsonObject("args").get("returnByDeath").getAsBoolean();assert text(free,"status").equals("Pending")&&bank.account(PLAYER).get("balance").getAsLong()==10;
            bot.state(text(free,"id"),"Complete","");JsonObject paid=bot.receive("SyntheticUser whispers: buy obsidian 2",1005000,library);assert text(paid,"status").equals("Pending")&&bank.account(PLAYER).get("balance").getAsLong()==8;
            bank.saveKitbot(bank.kitbot(),paid,-1);assert bank.account(PLAYER).get("balance").getAsLong()==8 : "Replaying a charged order cannot debit twice";
            String paidId=text(paid,"id");bot.state(paidId,"Queued","");JsonObject task=object("{status:'Cancelled',runs:{}}");JsonObject run=object("{status:'Running',deliveryReceipt:{expected:2,delivered:0,uncertain:false}}");task.getAsJsonObject("runs").add(WORKER,run);
            bot.reconcile(Map.of(UUID.fromString(paidId),task));assert bank.account(PLAYER).get("balance").getAsLong()==8 : "Host cancellation alone cannot refund a still-running drop";
            run.addProperty("status","Cancelled");run.getAsJsonObject("deliveryReceipt").addProperty("uncertain",true);bot.reconcile(Map.of(UUID.fromString(paidId),task));assert bank.account(PLAYER).get("balance").getAsLong()==8 : "Uncertain drops require review";
            run.getAsJsonObject("deliveryReceipt").addProperty("uncertain",false);run.getAsJsonObject("deliveryReceipt").addProperty("delivered",1);bot.reconcile(Map.of(UUID.fromString(paidId),task));bot.reconcile(Map.of(UUID.fromString(paidId),task));assert bank.account(PLAYER).get("balance").getAsLong()==9 : "Only confirmed undelivered kits are refunded, exactly once";
            assert bot.receive("SyntheticUser whispers: kit obsidian 1",1010000,library)==null : "Credit purchases do not reset or bypass the free allowance";
            JsonObject blocked=bot.receive("SyntheticUser whispers: buy obsidian 2",1010000,library);assert text(blocked,"status").equals("Cooldown")&&text(blocked,"payment").equals("Credits")&&blocked.get("cooldownUntil").getAsLong()==1605000;
            assert bot.receive("SyntheticUser whispers: kit obsidian 1",1600000,library)==null : "A positive balance never shortens the free hour";
            assert text(bot.receive("SyntheticUser whispers: buy obsidian 2",1604999,library),"status").equals("Cooldown") : "Purchases enforce the full ten minutes";
            JsonObject another=bot.receive("SyntheticUser whispers: buy obsidian 2",1605000,library);assert text(another,"status").equals("Pending")&&bank.account(PLAYER).get("balance").getAsLong()==7;
            bot.submissionFailed(text(another,"id"),"Synthetic rejection",true);bot.submissionFailed(text(another,"id"),"Synthetic rejection",true);assert bank.account(PLAYER).get("balance").getAsLong()==9;
            JsonObject manual=bot.receive("SyntheticUser whispers: buy obsidian 1",2205000,library);String manualId=text(manual,"id");bot.state(manualId,"Cancelled","Synthetic uncertain drop");bot.resolveCredit(manualId,0,"Synthetic operator verification");bot.resolveCredit(manualId,0,"Synthetic operator verification");assert bank.account(PLAYER).get("balance").getAsLong()==9;KitBot reviewed=bot;rejects(()->reviewed.resolveCredit(manualId,1,"Conflicting count"));
            assert bank.history(PLAYER).getAsJsonArray("settlements").asList().stream().anyMatch(s->text(s.getAsJsonObject(),"orderId").equals(manualId)) : "Manual reviews retain a durable financial receipt";
            JsonObject poor=object("{name:'UnfundedUser'}");poor.addProperty("uuid",OTHER);bot.observeSender("UnfundedUser whispers: buy obsidian 1",poor);
            assert text(bot.receive("UnfundedUser whispers: buy obsidian 1",1020000,library),"status").equals("InsufficientCredits")&&!bank.account(OTHER).get("exists").getAsBoolean();
            assert bot.receive("SyntheticUser whispers: kit obsidian 1",2800000,library)==null : "Credit offers respect the purchase timer";
            bank.adjust(UUID.randomUUID().toString(),PLAYER,"SyntheticUser",-9,"Synthetic balance depletion","test");
            assert text(bot.receive("SyntheticUser whispers: buy obsidian 1",2210000,library),"status").equals("Cooldown") : "Spending or losing the last credit cannot erase an active purchase cooldown";
            assert bot.cooldownMessage("SyntheticUser",2800000).endsWith("30m 0s.") : "Free timer is independent of balance and paid orders";
            bank.adjust(UUID.randomUUID().toString(),PLAYER,"SyntheticUser",2,"Synthetic reward","test");
            assert bot.receive("SyntheticUser whispers: kit obsidian 1",2800000,library)==null : "Funding does not alter either timer";
            JsonObject lastPaid=bot.receive("SyntheticUser whispers: buy obsidian 1",4595000,library);assert text(lastPaid,"status").equals("Pending");bot.state(text(lastPaid,"id"),"Complete","");
            JsonObject nextFree=bot.receive("SyntheticUser whispers: kit obsidian 1",4600000,library);assert text(nextFree,"status").equals("Pending") : "Free allowance remains available while purchases are on cooldown";bot.state(text(nextFree,"id"),"Complete","");
            assert text(bot.receive("SyntheticUser whispers: buy obsidian 1",4605000,library),"status").equals("Cooldown");
            KitBot restarted=new KitBot(root,bank);restarted.observeSender("SyntheticUser whispers: buy obsidian 1",sender);assert text(restarted.receive("SyntheticUser whispers: buy obsidian 1",4605000,library),"status").equals("Cooldown") : "Host restart retains the paid cooldown";
            JsonObject policy=bot.creditPolicy("SyntheticUser",4605000);assert policy.get("freeCooldownSeconds").getAsInt()==3600&&policy.get("creditCooldownSeconds").getAsInt()==600;
            assert policy.get("maximumCreditKitsPerRequest").getAsInt()==27&&policy.get("maximumFreeKitsPerRequest").getAsInt()==1;
            bank.adjust(UUID.randomUUID().toString(),OTHER,"UnfundedUser",40,"Synthetic delivery-cap check","test");
            JsonObject rejected=bot.receive("UnfundedUser whispers: buy obsidian 28",6000000,library);assert text(rejected,"status").equals("LimitExceeded")&&rejected.get("maxKits").getAsInt()==27&&bank.account(OTHER).get("balance").getAsLong()==40;
            JsonObject full=bot.receive("UnfundedUser whispers: buy obsidian 27",6005000,library);assert text(full,"status").equals("Pending")&&full.getAsJsonObject("submission").getAsJsonObject("args").get("count").getAsInt()==27&&bank.account(OTHER).get("balance").getAsLong()==13 : "The paid cap permits exactly 27 and rejected excess never charges or starts its purchase timer";
            bot.state(text(full,"id"),"Complete","");assert text(bot.receive("UnfundedUser whispers: kit obsidian 2",6010000,library),"status").equals("LimitExceeded") : "Raising the paid cap never raises the free cap";
            JsonObject project=config.deepCopy(),entry=object("{name:'SyntheticUser',maxKits:2,cooldownSeconds:0}");entry.addProperty("uuid",PLAYER);project.getAsJsonArray("whitelist").add(entry);bot.configure(project,text(bot.view(),"revision"));
            assert bot.creditPolicy("SyntheticUser",7000000).get("maximumCreditKitsPerRequest").getAsInt()==27&&bot.creditPolicy("SyntheticUser",7000000).get("maximumFreeKitsPerRequest").getAsInt()==2 : "Whitelisted users have the same paid cap with their separate configured free cap";
            bot.configure(config,text(bot.view(),"revision"));
            Files.writeString(root.resolve("kitbot.json"),"{}");KitBot reloaded=new KitBot(root,bank);assert reloaded.view().getAsJsonObject("config").equals(config)&&reloaded.view().getAsJsonObject("requests").has(paidId) : "SQLite is authoritative after migration";
            JsonObject originalStock=KitCategories.stock(StashCatalog.get(root,"Default",scope,"SyntheticDepot"));String kit=originalStock.keySet().iterator().next(),family=KitTypes.family(originalStock.getAsJsonObject(kit));
            bot.observeSender("UnfundedUser whispers: buy obsidian 1",poor);long beforeExcluded=bank.account(OTHER).get("balance").getAsLong();JsonObject awaiting=bot.receive("UnfundedUser","obsidian",1,"",7900000,library,null,"Credits");assert text(awaiting,"status").equals("Pending");
            JsonObject excluded=config.deepCopy();JsonArray exclusions=new JsonArray();exclusions.add(family);excluded.add("excludedKitFamilies",exclusions);bot.configure(excluded,text(bot.view(),"revision"));bot.observeSender("UnfundedUser whispers: kits",poor);
            assert bot.pending().isEmpty()&&bank.account(OTHER).get("balance").getAsLong()==beforeExcluded&&text(bot.view().getAsJsonObject("requests").getAsJsonObject(text(awaiting,"id")),"status").equals("Failed") : "An accepted order that becomes excluded is rejected before dispatch and refunded exactly once";
            assert bot.mikeCatalog("UnfundedUser").asList().stream().noneMatch(e->e.getAsJsonObject().get("inStock").getAsBoolean()) : "Excluded families never contribute to Mike's menu or category availability";
            assert !text(bot.listing("UnfundedUser whispers: kits"),"text").contains("Synthetic paving");
            assert bot.inspect("UnfundedUser",object("{kit:'"+KitBotContents.reference(kit)+"'}")).getAsJsonArray("kits").isEmpty() : "Known exact references cannot reveal excluded variants";
            long balance=bank.account(OTHER).get("balance").getAsLong();
            assert text(bot.receive("UnfundedUser","obsidian",1,"",9000000,library,null,"Credits"),"status").equals("OutOfStock");
            assert text(bot.receive("UnfundedUser","obsidian",1,KitBotContents.reference(kit),9005000,library,null,"Credits"),"status").equals("OutOfStock")&&bank.account(OTHER).get("balance").getAsLong()==balance : "Category and exact paid requests both deny excluded kits without charging";
            bot.configure(config,text(bot.view(),"revision"));assert bot.view().getAsJsonObject("config").getAsJsonArray("excludedKitFamilies").equals(exclusions) : "Older settings clients cannot silently erase hidden exclusions";
            assert KitCategories.stock(StashCatalog.get(root,"Default",scope,"SyntheticDepot")).equals(originalStock) : "Kitbot exclusions never erase stash inventory";
            JsonObject cleared=config.deepCopy();cleared.add("excludedKitFamilies",new JsonArray());bot.configure(cleared,text(bot.view(),"revision"));bot.observeSender("UnfundedUser whispers: kits",poor);
            assert bot.mikeCatalog("UnfundedUser").asList().stream().anyMatch(e->e.getAsJsonObject().get("inStock").getAsBoolean());
            JsonObject target=object("{id:'00000000-0000-0000-0000-000000000091',name:'GiftRecipient'}");long beforeTimeout=bank.account(OTHER).get("balance").getAsLong();
            JsonObject giftOrder=bot.receive("UnfundedUser","obsidian",2,"",20_000_000,library,target,"Credits");String giftId=text(giftOrder,"id");bot.state(giftId,"Queued","");
            JsonObject timedTask=object("{status:'Running',runs:{}}"),timedRun=object("{status:'Running',deliveryReceipt:{expected:2,delivered:0,uncertain:false,failureReason:'tpa_timeout'}}");timedTask.getAsJsonObject("runs").add(WORKER,timedRun);
            bot.reconcile(Map.of(UUID.fromString(giftId),timedTask));assert bot.pendingNotices(30_000_000).isEmpty()&&bank.account(OTHER).get("balance").getAsLong()==beforeTimeout-2 : "Do not announce a refund while recovery is still running";
            timedTask.addProperty("status","Cancelled");timedRun.addProperty("status","Cancelled");bot.reconcile(Map.of(UUID.fromString(giftId),timedTask));bot.reconcile(Map.of(UUID.fromString(giftId),timedTask));
            assert bank.account(OTHER).get("balance").getAsLong()==beforeTimeout&&bot.pendingNotices(30_000_000).size()==1;
            JsonObject notice=bot.pendingNotices(30_000_000).get(0);String noticeId=text(notice,"id"),command=MikeKitHandler.whisper(text(notice,"name"),text(notice,"text"));
            assert text(notice,"name").equals("UnfundedUser")&&command.startsWith("/msg UnfundedUser ")&&text(notice,"text").contains("GiftRecipient")&&text(notice,"text").contains("credits were refunded")&&text(notice,"text").contains("cooldown still applies") : "Notify the requester, never the gift recipient";
            bot.noticeAttempted(noticeId,30_000_000);assert bot.pendingNotices(30_029_999).isEmpty()&&bot.pendingNotices(30_030_000).size()==1;
            KitBot outboxReload=new KitBot(root,bank);assert outboxReload.pendingNotices(30_030_000).get(0).equals(bot.pendingNotices(30_030_000).get(0)) : "Queued notice and retry deadline survive a restart";
            bot.noticeSent(noticeId,OTHER,"Default",scope,command,30_030_000);assert bot.pendingNotices(30_030_000).size()==1 : "Another worker cannot acknowledge this notice";
            bot.noticeSent(noticeId,WORKER,"Default",scope,command.replace("/msg UnfundedUser ","/msg GiftRecipient "),30_030_000);assert bot.pendingNotices(30_030_000).size()==1 : "A DM to the recipient is not a requester notification";
            bot.noticeSent(noticeId,WORKER,"Default",scope,command,30_030_000);bot.reconcile(Map.of(UUID.fromString(giftId),timedTask));assert bot.pendingNotices(30_040_000).isEmpty();
            assert new KitBot(root,bank).pendingNotices(30_040_000).isEmpty() : "Acknowledged notices do not replay after restart";
            JsonObject freeTimeout=bot.receive("UnfundedUser","obsidian",1,"",21_000_000,library,target);String freeId=text(freeTimeout,"id");bot.state(freeId,"Queued","");
            JsonObject freeTask=timedTask.deepCopy();freeTask.getAsJsonObject("runs").getAsJsonObject(WORKER).getAsJsonObject("deliveryReceipt").addProperty("expected",1);bot.reconcile(Map.of(UUID.fromString(freeId),freeTask));
            assert bot.pendingNotices(31_000_000).size()==1&&text(bot.pendingNotices(31_000_000).get(0),"text").contains("No credits were spent")&&bank.account(OTHER).get("balance").getAsLong()==beforeTimeout : "Free-order notices never invent a refund";

            long offerAt=22_000_000,beforeOffer=bank.account(OTHER).get("balance").getAsLong();int requestsBefore=bot.view().getAsJsonObject("requests").size();
            JsonObject offer=bot.receive("UnfundedUser whispers: kit obsidian 2",offerAt,library);
            assert text(offer,"status").equals("Listing")&&text(offer,"text").contains("Spend 2 credits")&&text(offer,"text").contains("Reply yes within 2m")&&bot.view().getAsJsonObject("requests").size()==requestsBefore&&bank.account(OTHER).get("balance").getAsLong()==beforeOffer : "Offer costs nothing and creates no order or timer";
            assert bot.confirmCredits("SyntheticUser whispers: yes",offerAt+5000,library)==null : "Another user cannot confirm the offer";
            JsonObject confirmed=bot.confirmCredits("UnfundedUser whispers: YES please!",offerAt+5000,library);
            assert text(confirmed,"status").equals("Pending")&&text(confirmed,"payment").equals("Credits")&&text(confirmed,"kit").equals(KitBotContents.reference(kit))&&confirmed.get("count").getAsInt()==2&&bank.account(OTHER).get("balance").getAsLong()==beforeOffer-2;
            assert bot.confirmCredits("UnfundedUser whispers: yes",offerAt+6000,library)==null&&bank.account(OTHER).get("balance").getAsLong()==beforeOffer-2 : "Confirmation is single-use";
            bot.state(text(confirmed,"id"),"Complete","");assert bot.receive("UnfundedUser whispers: kit obsidian 1",offerAt+10_000,library)==null : "Do not offer a purchase during its own cooldown";
            long nextOffer=22_700_000;assert text(bot.offerCredits("UnfundedUser","obsidian",3,KitBotContents.reference(kit),nextOffer,library,target),"text").contains("for GiftRecipient");
            JsonObject confirmedGift=bot.confirmCredits("UnfundedUser whispers: use my credits",nextOffer+5000,library);
            assert text(confirmedGift,"recipient").equals(text(target,"id"))&&text(confirmedGift,"requester").equals(OTHER)&&confirmedGift.get("count").getAsInt()==3 : "Gift recipient and exact choice survive confirmation";bot.state(text(confirmedGift,"id"),"Complete","");
            long later=23_400_000;beforeOffer=bank.account(OTHER).get("balance").getAsLong();
            bot.offerCredits("UnfundedUser","obsidian",1,"",later,library,null);assert text(bot.confirmCredits("UnfundedUser whispers: no thanks",later+5000,library),"text").contains("cancelled");
            bot.offerCredits("UnfundedUser","obsidian",1,"",later+10_000,library,null);assert text(bot.confirmCredits("UnfundedUser whispers: yes",later+130_000,library),"text").contains("expired");
            bot.offerCredits("UnfundedUser","obsidian",1,"",later+140_000,library,null);assert bot.confirmCredits("UnfundedUser whispers: actually something else",later+145_000,library)==null&&bot.confirmCredits("UnfundedUser whispers: yes",later+150_000,library)==null : "Changing the subject clears consent";
            bot.offerCredits("UnfundedUser","obsidian",1,"",later+160_000,library,null);KitBot afterRestart=new KitBot(root,bank);afterRestart.observeSender("UnfundedUser whispers: yes",poor);assert afterRestart.confirmCredits("UnfundedUser whispers: yes",later+165_000,library)==null : "Restart never restores unaccepted spending consent";
            bot.configure(cleared,text(bot.view(),"revision"));bot.observeSender("UnfundedUser whispers: yes",poor);assert bot.confirmCredits("UnfundedUser whispers: yes",later+165_000,library)==null : "Settings changes clear offers";
            assert bank.account(OTHER).get("balance").getAsLong()==beforeOffer : "Declines, expiry and stale consent never charge";
            bot.offerCredits("UnfundedUser","obsidian",2,"",later+170_000,library,null);long remaining=bank.account(OTHER).get("balance").getAsLong();bank.adjust(UUID.randomUUID().toString(),OTHER,"UnfundedUser",-remaining,"Synthetic concurrent spending","test");
            assert text(bot.confirmCredits("UnfundedUser whispers: yes",later+175_000,library),"status").equals("InsufficientCredits")&&bank.account(OTHER).get("balance").getAsLong()==0 : "Confirmation rechecks balance";
            assert bot.offerCredits("UnfundedUser","obsidian",1,"",later+176_000,library,null)==null : "Do not offer unavailable credit funds";
            bank.adjust(UUID.randomUUID().toString(),OTHER,"UnfundedUser",5,"Synthetic reward","test");bot.offerCredits("UnfundedUser","obsidian",1,"",later+180_000,library,null);
            for(int x=100;x<=105;x++)StashCatalog.save(root,"Default",scope,bounds,object("{x:"+x+",y:90,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}"));
            assert text(bot.confirmCredits("UnfundedUser whispers: yes",later+185_000,library),"status").equals("OutOfStock")&&bank.account(OTHER).get("balance").getAsLong()==5 : "Confirmation rechecks actual eligible stock, without substitution or charge";


        }
    }
    private static void toolRoles()throws Exception{
        JsonObject settings=MikeKitHandler.defaults();settings.addProperty("endpoint","http://127.0.0.1:11434");settings.addProperty("model","synthetic");
        JsonObject base=MikeKitHandler.requestBody(settings,"hello",1,new JsonArray(),new JsonArray(),"SyntheticUser"),normal=MikeKitHandler.bankContext(base,false),admin=MikeKitHandler.bankContext(base,true);
        assert !normal.toString().contains("gift_credits")&&!normal.toString().contains("revoke_credits")&&admin.toString().contains("VERIFIED ADMINISTRATOR");
        assert !text(normal.getAsJsonArray("messages").get(0).getAsJsonObject(),"content").equals(text(admin.getAsJsonArray("messages").get(0).getAsJsonObject(),"content"));
        for(String operation:List.of("gift_credits","revoke_credits")){
            JsonObject response=object("{role:'assistant',content:'',tool_calls:[{function:{name:'"+operation+"',arguments:{player:'SyntheticUser',amount:5}}}]}");rejects(()->MikeKitHandler.decision(response,Set.of(),false,false));assert MikeKitHandler.decision(response,Set.of(),false,true).bank()!=null;
        }
        JsonObject otherBalance=object("{role:'assistant',tool_calls:[{function:{name:'get_credit_balance',arguments:{player:'OtherUser'}}}]}");rejects(()->MikeKitHandler.decision(otherBalance,Set.of(),false,false));
        var confirm=MikeKitHandler.decision(object("{role:'assistant',tool_calls:[{function:{name:'request_kits',arguments:{category:'paving',quantity:27,payment:'Credits'}}}]}"),Set.of("paving"));
        assert MikeKitHandler.creditConfirmation(confirm).contains("27 credits")&&MikeKitHandler.creditConfirmation(confirm).contains("buy paving 27");
        var gift=MikeKitHandler.decision(object("{role:'assistant',tool_calls:[{function:{name:'request_kits',arguments:{category:'paving',quantity:3,payment:'Credits',recipient:'SyntheticFriend'}}}]}"),Set.of("paving"));
        assert MikeKitHandler.creditConfirmation(gift).contains("buy 3 paving kits for SyntheticFriend");
        assert MikeKitHandler.creditSpendingRequested("buy 2 pvp kits")&&MikeKitHandler.creditSpendingRequested("use my credits for pvp");assert !MikeKitHandler.creditSpendingRequested("thanks for the kit")&&!MikeKitHandler.creditSpendingRequested("do not spend my credits");
        Path root=Files.createTempDirectory("synthetic-bank-roles-");KitBot bot=new KitBot(root);JsonObject config=object("{enabled:true,publicAccess:false,crew:'Default',scope:'synthetic.invalid\\nminecraft:the_end',source:'SyntheticDepot',minimumStock:270,cooldownSeconds:3600,whitelist:[{name:'SyntheticAdmin',role:'Administrator'}]}");config.addProperty("worker",WORKER);config.getAsJsonArray("whitelist").get(0).getAsJsonObject().addProperty("uuid",PLAYER);bot.configure(config,text(bot.view(),"revision"));
        assert !bot.administrator("SyntheticAdmin");JsonObject identity=object("{name:'SyntheticAdmin'}");identity.addProperty("uuid",PLAYER);bot.observeSender("SyntheticAdmin whispers: hello",identity);assert bot.administrator("SyntheticAdmin");
        bot.observeSender("SyntheticAdmin whispers: hello",null);assert !bot.administrator("SyntheticAdmin") : "A name alone cannot retain administrator authority";
        identity.addProperty("uuid",OTHER);rejects(()->bot.observeSender("SyntheticAdmin whispers: hello",identity));assert !bot.administrator("SyntheticAdmin");
    }
    private static void receipts(){
        JsonObject run=object("{package:{entry:'task-kit-delivery'},args:{destination:'Player',count:5},stack:[{state:{phase:'dropping',total:1,batch:4},native:{remaining:2,pendingDrop:{issued:true}}}]}");
        JsonObject receipt=DeliveryReceipt.checkpoint(run);assert receipt.get("delivered").getAsInt()==3&&receipt.get("uncertain").getAsBoolean();
        JsonObject frame=run.getAsJsonArray("stack").get(0).getAsJsonObject();frame.remove("native");frame.add("result",object("{dropped:4}"));assert DeliveryReceipt.checkpoint(run).get("delivered").getAsInt()==5;
        run.add("workflowResult",object("{delivered:5}"));run.add("stack",new JsonArray());assert !DeliveryReceipt.checkpoint(run).get("uncertain").getAsBoolean();
        rejects(()->DeliveryReceipt.checked(object("{expected:5,delivered:6,uncertain:false}"),5));rejects(()->DeliveryReceipt.checked(object("{expected:4,delivered:0,uncertain:false}"),5));
        String id=UUID.randomUUID().toString();JsonObject task=object("{status:'Running',package:{entry:'task-kit-delivery'},args:{destination:'Player',count:5}}"),execution=object("{status:'Running'}");execution.addProperty("id",id);JsonObject report=object("{status:'Running',detail:'Synthetic progress',deliveryReceipt:{expected:5,delivered:6,uncertain:false}}");report.addProperty("run",id);JsonObject before=execution.deepCopy();rejects(()->TaskWire.applyStatus(task,execution,report,true));assert execution.equals(before) : "Reject malformed financial evidence before mutating execution state";
        JsonObject timeout=object("{expected:5,delivered:0,uncertain:false,failureReason:'tpa_timeout'}");assert DeliveryReceipt.checked(timeout,5).has("failureReason");
        JsonObject contradictory=timeout.deepCopy();contradictory.addProperty("delivered",1);rejects(()->DeliveryReceipt.checked(contradictory,5));
        report.getAsJsonObject("deliveryReceipt").addProperty("delivered",3);TaskWire.applyStatus(task,execution,report,true);report.getAsJsonObject("deliveryReceipt").addProperty("delivered",2);TaskWire.applyStatus(task,execution,report,true);assert execution.getAsJsonObject("deliveryReceipt").get("delivered").getAsInt()==3 : "Confirmed drops cannot regress after a stale checkpoint";
        JsonObject conflicting=report.deepCopy();conflicting.add("deliveryReceipt",timeout);JsonObject preserved=execution.deepCopy();rejects(()->TaskWire.applyStatus(task,execution,conflicting,true));assert execution.equals(preserved) : "Contradictory timeout evidence must not partially mutate a confirmed delivery";

    }
}
