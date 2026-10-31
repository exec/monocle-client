package dev.monocle.client.systems.modules.player;

import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.DoubleSetting;
import dev.monocle.client.settings.EnumSetting;
import dev.monocle.client.settings.IntSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

import java.util.concurrent.ThreadLocalRandom;

/** Spoofs transmitted rotation or crouching without changing local movement or camera controls. */
public class Derp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>().name("mode").description("Rotation or crouching pattern to use.").defaultValue(Mode.Spin).onChanged(value -> resetTeabag()).build());
    private final Setting<Double> spinRevolutions = sgGeneral.add(new DoubleSetting.Builder().name("spin-revolutions-per-second").description("Full turns per second while spinning.").defaultValue(2).range(0.1, 10).sliderRange(0.1, 5).visible(() -> mode.get() == Mode.Spin).build());
    private final Setting<Integer> randomDelay = sgGeneral.add(new IntSetting.Builder().name("random-delay").description("Ticks between completely random directions.").defaultValue(10).range(1, 200).sliderRange(1, 40).visible(() -> mode.get() == Mode.Random).build());
    private final Setting<Integer> upDownRate = sgGeneral.add(new IntSetting.Builder().name("up-down-rate").description("Ticks between looking straight up and down.").defaultValue(10).range(1, 200).sliderRange(1, 40).visible(() -> mode.get() == Mode.ForwardUpDown).build());
    private final Setting<Integer> teabagDelay = sgGeneral.add(new IntSetting.Builder().name("teabag-delay").description("Ticks between crouching and standing. One tick is fastest; movement and camera are unchanged.").defaultValue(1).range(1, 200).sliderRange(1, 20).visible(() -> mode.get() == Mode.Teabag).build());

    private float yaw, pitch;
    private int ticks;
    private boolean injecting, crouching, courier;

    public Derp() { super(Categories.Player, "derp", "Spoofs rotation or rapid crouching without moving your camera."); }

    @Override public void onActivate() {
        yaw = mc.player == null ? 0 : mc.player.getYRot();
        pitch = mc.player == null ? 0 : mc.player.getXRot();
        resetTeabag();
    }

    @Override public void onDeactivate() { courier=false;resetTeabag(); }

    /** Temporary courier overlay; never edits the user's saved mode or timing settings. */
    public void courierCelebration(boolean active){
        if(active){
            if(courier)return;
            if(!isActive())enable();
            resetTeabag();courier=true;
        }else if(courier){
            courier=false;
            if(isActive())disable();else resetTeabag();
        }
    }

    public void courierAtStash(){
        courier=false;
        if(isActive())disable();else resetTeabag();
    }

    private void resetTeabag() {
        if (crouching && mc.player != null && mc.getConnection() != null)
            mc.getConnection().send(new ServerboundPlayerInputPacket(mc.player.input.keyPresses));
        crouching = false;
        ticks = 0;
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if(courier){
            if(ticks%randomDelay.get()==0)randomize();
            yaw+=(float)(spinRevolutions.get()*18);crouching=teabagPhase(ticks++,1);sendRotation();return;
        }
        switch (mode.get()) {
            case Spin -> yaw += (float) (spinRevolutions.get() * 18);
            case Random -> { if (ticks++ % randomDelay.get() == 0) randomize(); }
            case ForwardUpDown -> {
                yaw = mc.player.getYRot();
                if (ticks++ % upDownRate.get() == 0) pitch = pitch < 0 ? 90 : -90;
            }
            case Teabag -> crouching = teabagPhase(ticks++, teabagDelay.get());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (!rotating() || injecting || !(event.packet instanceof ServerboundMovePlayerPacket packet) || mc.player == null) return;
        event.cancel();
        injecting = true;
        try {
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                packet.getX(mc.player.getX()), packet.getY(mc.player.getY()), packet.getZ(mc.player.getZ()),
                yaw, pitch, packet.isOnGround(), mc.player.horizontalCollision));
        } finally { injecting = false; }
    }

    private void sendRotation(){
        if(mc.getConnection()==null)return;
        // Vanilla can skip movement packets while standing still during a home warmup.
        injecting=true;
        try{mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw,pitch,mc.player.onGround(),mc.player.horizontalCollision));}
        finally{injecting=false;}
    }

    private void randomize() {
        yaw = ThreadLocalRandom.current().nextFloat() * 360 - 180;
        pitch = ThreadLocalRandom.current().nextFloat() * 180 - 90;
    }

    public float visualYaw() { return yaw; }
    public float visualPitch() { return pitch; }
    public boolean rotating() { return isActive() && rotationMode(mode.get(),courier); }
    public boolean teabagging() { return isActive() && (courier || mode.get() == Mode.Teabag); }
    static boolean rotationMode(Mode mode,boolean courier){return courier||mode!=Mode.Teabag;}
    public boolean visualCrouching() { return crouching; }
    public Input serverInput(Input input) { return teabagInput(input, crouching); }
    static boolean teabagPhase(int tick, int delay) { return (tick / delay & 1) == 0; }
    static Input teabagInput(Input input, boolean crouching) {
        return new Input(input.forward(), input.backward(), input.left(), input.right(), input.jump(), input.shift() || crouching, input.sprint());
    }

    public enum Mode {
        Spin("Spin"), Random("Random"), ForwardUpDown("Forward Up/Down"), Teabag("Teabag");
        private final String title;
        Mode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
}
