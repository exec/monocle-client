package dev.monocle.client.systems.bots;

/** Pure command/checkpoint lifecycle: no game connection or real inventory. */
final class BotRespawnReturnCheck {
    static void run(){
        var r=new BotRespawnReturn();
        assert r.next(60_999,61_000,true,false)==BotRespawnReturn.Next.Wait;
        assert r.next(61_000,61_000,true,false)==BotRespawnReturn.Next.Checkpoint;
        var intent=r.snapshot();assert r.next(61_001,61_000,true,false)==BotRespawnReturn.Next.Wait : "No command before durable acknowledgement";
        r.checkpointSaved();assert r.next(61_002,61_000,true,false)==BotRespawnReturn.Next.Kill;
        assert r.next(61_003,61_000,true,false)==BotRespawnReturn.Next.Wait : "One command per checkpoint";
        var restored=new BotRespawnReturn();restored.restore(intent);
        assert restored.next(61_004,0,true,false)==BotRespawnReturn.Next.Wait : "A restored intent never immediately replays /kill";
        assert restored.next(121_999,0,true,false)==BotRespawnReturn.Next.Wait;
        assert restored.next(122_000,0,true,false)==BotRespawnReturn.Next.Checkpoint : "Retry on the first tick the independent cooldown expires";
        r.respawned(61_500);assert r.next(61_999,0,true,false)==BotRespawnReturn.Next.Wait : "Give the applied world/player replacement time to settle";
        assert r.next(62_000,0,true,true)==BotRespawnReturn.Next.Arrived;
        assert r.next(62_000,0,true,false)==BotRespawnReturn.Next.WrongSpawn : "An incorrect bed must not start a death loop";
        var dead=new BotRespawnReturn();dead.next(1000,0,true,false);dead.checkpointSaved();dead.next(1001,0,true,false);
        assert dead.next(1100,0,false,false)==BotRespawnReturn.Next.Respawn;
        assert dead.next(1600,0,true,true)==BotRespawnReturn.Next.Arrived;
        var intervening=new BotRespawnReturn();intervening.next(1000,0,true,false);intervening.checkpointSaved();
        assert intervening.next(1001,62_000,true,false)==BotRespawnReturn.Next.Wait : "An observed manual command can extend the shared cooldown even after preparation";
        var alreadyDead=new BotRespawnReturn();assert alreadyDead.next(1000,61_000,false,false)==BotRespawnReturn.Next.Respawn;
        assert alreadyDead.next(1500,61_000,true,true)==BotRespawnReturn.Next.Arrived : "An external death has already discarded cargo; do not kill again after reaching the correct bed";
        var rejected=new BotRespawnReturn();
        for(int i=0;i<3;i++){long now=1000+i*61_000;assert rejected.next(now,0,true,false)==BotRespawnReturn.Next.Checkpoint;rejected.checkpointSaved();assert rejected.next(now+1,0,true,false)==BotRespawnReturn.Next.Kill;}
        assert rejected.next(184_000,0,true,false)==BotRespawnReturn.Next.Exhausted;
        var bad=intent.deepCopy();bad.addProperty("issuedAt",Long.MAX_VALUE);try{new BotRespawnReturn().restore(bad);throw new AssertionError("Invalid clock accepted");}catch(IllegalArgumentException expected){}
        System.out.println("Respawn-return checks passed: exact 61-second deadline, durable one-shot commands, respawn verification, wrong beds and bounded retries.");
    }
}
