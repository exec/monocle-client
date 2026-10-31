package dev.monocle.client.systems.modules.player;

import dev.monocle.client.mixin.AvatarRendererMixin;
import dev.monocle.client.mixin.LocalPlayerMixin;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Input;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;

/** ./gradlew derpCheck */
public final class DerpTest {
    public static void main(String[] args) throws Exception {
        boolean assertions=false;assert assertions=true;if(!assertions)throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        for(int delay:List.of(1,3,10,200))for(int tick=0;tick<delay*6;tick++){
            assert Derp.teabagPhase(tick,delay)==Derp.teabagPhase(tick+2*delay,delay);
            assert Derp.teabagPhase(tick,delay)!=Derp.teabagPhase(tick+delay,delay);
            if(tick>0)assert (Derp.teabagPhase(tick,delay)!=Derp.teabagPhase(tick-1,delay))==(tick%delay==0);
        }
        for(int bits=0;bits<128;bits++)for(boolean crouched:List.of(false,true)){
            Input input=new Input((bits&1)!=0,(bits&2)!=0,(bits&4)!=0,(bits&8)!=0,(bits&16)!=0,(bits&32)!=0,(bits&64)!=0);
            Input packet=Derp.teabagInput(input,crouched);
            assert packet.forward()==input.forward()&&packet.backward()==input.backward()&&packet.left()==input.left()&&packet.right()==input.right();
            assert packet.jump()==input.jump()&&packet.sprint()==input.sprint();
            assert packet.shift()==(input.shift()||crouched) : "Physical crouching takes precedence over the cosmetic standing phase";
            assert input.shift()==((bits&32)!=0) : "The local input object is never changed";
        }
        for(var mode:Derp.Mode.values()){
            assert Derp.rotationMode(mode,true) : "Courier celebration combines rotation with teabag in every saved mode";
            assert Derp.rotationMode(mode,false)==(mode!=Derp.Mode.Teabag) : "Normal teabag alone preserves transmitted rotation";
        }
        var send=code(Derp.class,"onSend");int guard=-1,cancel=-1;
        for(int i=0;i<send.size();i++)if(send.get(i) instanceof InvokeInstruction call){if(call.name().equalsString("rotating"))guard=i;if(call.name().equalsString("cancel"))cancel=i;}
        assert guard>=0&&guard<cancel&&send.subList(guard,cancel).stream().anyMatch(e->e instanceof ReturnInstruction) : "Teabag-only mode passes through packets; combined celebration also spoofs rotation";
        assert calls(Derp.class,"courierAtStash").containsAll(List.of("disable","resetTeabag")) : "Stash arrival always clears the active module and transmitted crouch";
        assert calls(Derp.class,"onTick").contains("sendRotation")&&calls(Derp.class,"sendRotation").contains("send") : "A stationary courier still transmits its spinning/random rotation each tick";

        assert calls(AvatarRendererMixin.class,"extractRenderState$rotations").contains("rotating") : "Combined rotation and crouch both reach third-person render state";
        assert calls(LocalPlayerMixin.class,"isSneaking").containsAll(List.of("teabagging","serverInput"));
        assert calls(AvatarRendererMixin.class,"extractRenderState$rotations").containsAll(List.of("teabagging","visualCrouching","visualYaw","visualPitch"));
        for(var method:List.of("onActivate","onDeactivate"))assert calls(Derp.class,method).contains("resetTeabag");
        for(var method:compiled(Derp.class).methods())if(method.code().isPresent()){
            var calls=calls(Derp.class,method.methodName().stringValue());
            assert Collections.disjoint(calls,List.of("setShiftKeyDown","setPose","setDown","setYRot","setXRot")) : "Derp cannot change local stance, keys or camera";
        }
        assert compiled(LocalPlayerMixin.class).methods().stream().filter(m->m.methodName().equalsString("isSneaking")).findFirst().orElseThrow().code().orElseThrow().elementList().stream()
            .noneMatch(e->e instanceof FieldInstruction f&&f.opcode()==Opcode.PUTFIELD) : "Packet expression rewriting never writes the actual input field";
        for(var element:code(AvatarRendererMixin.class,"extractRenderState$rotations"))
            if(element instanceof FieldInstruction f&&f.opcode()==Opcode.PUTFIELD)assert f.owner().asInternalName().endsWith("AvatarRenderState") : "Third-person spoofing only writes render state";
        System.out.println("Derp checks passed: alternating delays, all input flags, real crouch preservation, rotation passthrough and render-only stance wiring.");
    }
    private static ClassModel compiled(Class<?> type)throws Exception{
        try(var bytes=type.getResourceAsStream(type.getSimpleName()+".class")){return ClassFile.of().parse(bytes.readAllBytes());}
    }
    private static List<String> calls(Class<?> type,String name)throws Exception{
        return code(type,name).stream()
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(c->c.name().stringValue()).toList();
    }
    private static List<CodeElement> code(Class<?> type,String name)throws Exception{
        return compiled(type).methods().stream().filter(m->m.methodName().equalsString(name)).findFirst().orElseThrow().code().orElseThrow().elementList();
    }
}
