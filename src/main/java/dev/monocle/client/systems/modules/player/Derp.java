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

import java.util.concurrent.ThreadLocalRandom;

/** Overrides both the displayed and transmitted player rotation. */
public class Derp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>().name("mode").description("Rotation pattern to use.").defaultValue(Mode.Spin).build());
    private final Setting<Double> spinRevolutions = sgGeneral.add(new DoubleSetting.Builder().name("spin-revolutions-per-second").description("Full turns per second while spinning.").defaultValue(2).range(0.1, 10).sliderRange(0.1, 5).visible(() -> mode.get() == Mode.Spin).build());
    private final Setting<Integer> randomDelay = sgGeneral.add(new IntSetting.Builder().name("random-delay").description("Ticks between completely random directions.").defaultValue(10).range(1, 200).sliderRange(1, 40).visible(() -> mode.get() == Mode.Random).build());
    private final Setting<Integer> upDownRate = sgGeneral.add(new IntSetting.Builder().name("up-down-rate").description("Ticks between looking straight up and down.").defaultValue(10).range(1, 200).sliderRange(1, 40).visible(() -> mode.get() == Mode.ForwardUpDown).build());

    private float yaw, pitch;
    private int ticks;
    private boolean injecting;

    public Derp() { super(Categories.Player, "derp", "Overrides your rotation with a derpy pattern."); }

    @Override public void onActivate() {
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        ticks = 0;
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        switch (mode.get()) {
            case Spin -> yaw += (float) (spinRevolutions.get() * 18);
            case Random -> { if (ticks++ % randomDelay.get() == 0) randomize(); }
            case ForwardUpDown -> {
                yaw = mc.player.getYRot();
                if (ticks++ % upDownRate.get() == 0) pitch = pitch < 0 ? 90 : -90;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (injecting || !(event.packet instanceof ServerboundMovePlayerPacket packet) || mc.player == null) return;
        event.cancel();
        injecting = true;
        try {
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                packet.getX(mc.player.getX()), packet.getY(mc.player.getY()), packet.getZ(mc.player.getZ()),
                yaw, pitch, packet.isOnGround(), mc.player.horizontalCollision));
        } finally { injecting = false; }
    }

    private void randomize() {
        yaw = ThreadLocalRandom.current().nextFloat() * 360 - 180;
        pitch = ThreadLocalRandom.current().nextFloat() * 180 - 90;
    }

    public float visualYaw() { return yaw; }
    public float visualPitch() { return pitch; }

    public enum Mode {
        Spin("Spin"), Random("Random"), ForwardUpDown("Forward Up/Down");
        private final String title;
        Mode(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
}
