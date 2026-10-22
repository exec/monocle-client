package dev.monocle.client.pathing;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;

public final class PathPauseTest {
    public static void run()throws Exception{
        int[] callsCount=new int[2];
        IPathManager manager=(IPathManager)java.lang.reflect.Proxy.newProxyInstance(IPathManager.class.getClassLoader(),new Class<?>[]{IPathManager.class},(p,m,a)->{
            if(m.getName().equals("pause"))callsCount[0]++;else if(m.getName().equals("resume"))callsCount[1]++;else throw new AssertionError(m.getName());return null;
        });
        var owners=new PathManagers.PauseOwners();Object eat=new Object(),aura=new Object(),gap=new Object();
        owners.pause(manager,eat);owners.pause(manager,eat);
        assert callsCount[0]==1&&owners.held():"A held pause remains discoverable and acquisition is idempotent";
        owners.pause(manager,aura);owners.pause(manager,gap);
        owners.resume(manager,eat);owners.resume(manager,new Object());
        assert callsCount[1]==0:"Meal completion and unknown owners cannot release combat/other meals";
        owners.resume(manager,gap);assert callsCount[1]==0;
        owners.resume(manager,aura);assert callsCount[1]==1&&!owners.held();
        owners.resume(manager,aura);assert callsCount[1]==1:"Release is idempotent";
        Object first=new String("same"),second=new String("same");
        owners.pause(manager,first);owners.pause(manager,second);owners.resume(manager,first);
        assert callsCount[1]==1:"Owners use identity, not equal labels";
        owners.resume(manager,second);assert callsCount[0]==2&&callsCount[1]==2&&!owners.held();

        for(String name:java.util.List.of("player/AutoEat","player/AutoGap","combat/KillAura","combat/BowAimbot")){
            try(var bytes=PathPauseTest.class.getResourceAsStream("/dev/monocle/client/systems/modules/"+name+".class")){
                var code=ClassFile.of().parse(bytes.readAllBytes());
                var calls=code.methods().stream().flatMap(m->m.code().stream()).flatMap(c->c.elementList().stream())
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast)
                    .filter(i->i.owner().asInternalName().equals("dev/monocle/client/pathing/PathManagers")).toList();
                for(String method:java.util.List.of("pause","resume"))assert calls.stream().anyMatch(i->i.name().equalsString(method)&&i.type().stringValue().equals("(Ljava/lang/Object;)V")):name+" must use owned "+method;
                assert calls.stream().anyMatch(i->i.name().equalsString("hasNavigation")):"Already-paused navigation must still acquire another owner";
                if(name.equals("combat/BowAimbot"))assert code.methods().stream().filter(m->m.methodName().equalsString("onDeactivate"))
                    .flatMap(m->m.code().stream()).flatMap(c->c.elementList().stream()).anyMatch(e->e instanceof InvokeInstruction i&&i.name().equalsString("releasePathPause"));
            }
        }
        System.out.println("Owned path pause checks passed: overlapping meals/combat, reverse release, duplicate/unknown release, identity owners and module cleanup wiring.");
    }
}
