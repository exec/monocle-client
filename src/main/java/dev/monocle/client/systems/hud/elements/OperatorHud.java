package dev.monocle.client.systems.hud.elements;

import dev.monocle.client.settings.*;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.systems.hud.*;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.render.NotificationFeed;
import dev.monocle.client.utils.render.Notifications;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static dev.monocle.client.MonocleClient.mc;

/** One reusable card renderer for the operator-facing HUD panels. */
public final class OperatorHud extends HudElement {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    public enum Panel { Activity, Crew, Supplies, Navigation, Notifications }

    public static final HudElementInfo<OperatorHud> INFO = new HudElementInfo<>(Hud.GROUP, "operator-panel",
        "Operator Panel", "Compact live activity, crew, supply, navigation, or notification status.", OperatorHud::new);
    public static final HudElementInfo<OperatorHud>.Preset ACTIVITY = preset(Panel.Activity);
    public static final HudElementInfo<OperatorHud>.Preset CREW = preset(Panel.Crew);
    public static final HudElementInfo<OperatorHud>.Preset SUPPLIES = preset(Panel.Supplies);
    public static final HudElementInfo<OperatorHud>.Preset NAVIGATION = preset(Panel.Navigation);
    public static final HudElementInfo<OperatorHud>.Preset NOTIFICATIONS = preset(Panel.Notifications);

    private static HudElementInfo<OperatorHud>.Preset preset(Panel panel) {
        return INFO.addPreset(panel.toString(), hud -> hud.panel.set(panel));
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAppearance = settings.createGroup("Appearance");
    private final Setting<Panel> panel = sgGeneral.add(new EnumSetting.Builder<Panel>()
        .name("panel").description("Information shown in this card.").defaultValue(Panel.Activity).build());
    private final Setting<Integer> width = sgAppearance.add(new IntSetting.Builder()
        .name("width").description("Panel width in HUD pixels.").defaultValue(220).range(150, 420).build());
    private final Setting<Double> scale = sgAppearance.add(new DoubleSetting.Builder()
        .name("scale").description("Panel text and spacing scale.").defaultValue(1).range(.5, 2).sliderRange(.5, 1.5).build());
    private final Setting<Integer> maximumLines = sgAppearance.add(new IntSetting.Builder()
        .name("maximum-lines").description("Most detail lines shown in the panel.").defaultValue(5).range(2, 10).build());
    private final Setting<Boolean> background = sgAppearance.add(new BoolSetting.Builder()
        .name("background").description("Draw the Monocle panel background.").defaultValue(true).build());
    private final Setting<SettingColor> backgroundColor = sgAppearance.add(new ColorSetting.Builder()
        .name("background-color").description("Panel background color.").defaultValue(new SettingColor(19, 21, 29, 220)).visible(background::get).build());

    private List<String> lines = List.of("Waiting for live data");
    private String state = "Idle";
    private int timer;

    private OperatorHud() { super(INFO); }

    @Override public void tick(HudRenderer renderer) {
        if (timer-- <= 0) {
            update();
            timer = 9;
        }
        double s = scale.get(), line = renderer.textHeight(false, s) + 3 * s;
        setSize(width.get() * s, (lines.size() + 1) * line + 12 * s);
    }

    private void update() {
        if (!Utils.canUpdate()) {
            state = isInEditor() ? "Preview" : "Offline";
            lines = switch (panel.get()) {
                case Activity -> List.of("Healthy · 4.5 blocks/sec", "Highway workflow running");
                case Crew -> List.of("3 / 3 connected", "Workers synchronized");
                case Supplies -> List.of("12,288 paving blocks", "7 usable pickaxes", "Observed now");
                case Navigation -> List.of("0, 116, 100000 · South", "5.5 blocks/sec");
                case Notifications -> List.of("No recent alerts");
            };
            return;
        }

        HighwayBuilder highway = Modules.get().get(HighwayBuilder.class);
        Bots bots = Bots.get();
        lines = switch (panel.get()) {
            case Activity -> activity(highway, bots);
            case Crew -> crew(bots);
            case Supplies -> supplies(highway, bots);
            case Navigation -> navigation(highway);
            case Notifications -> notifications();
        };
        if (lines.size() > maximumLines.get()) lines = lines.subList(0, maximumLines.get());
    }

    private List<String> activity(HighwayBuilder highway, Bots bots) {
        if (highway.hasJob()) {
            state = highway.isHudHealthy() ? "Healthy" : "Attention";
            var result = new ArrayList<String>();
            result.add(highway.getPavingRate());
            result.add(highway.getRoadPrediction());
            if (!highway.isHudHealthy()) result.add(highway.getStatus());
            return result;
        }
        if (bots != null && bots.isActive()) {
            state = bots.connectionDetail().toLowerCase(Locale.ROOT).contains("connected") ? "Healthy" : "Attention";
            return List.of(bots.connectionDetail(), "No local highway job");
        }
        state = "Idle";
        return List.of("No active work");
    }

    private List<String> crew(Bots bots) {
        if (bots == null || !bots.isActive()) { state = "Offline"; return List.of("Workers host is disabled"); }
        var crew = bots.controlCrew();
        var view = crew.inspect();
        var members = crew.members();
        long connected = members.stream().filter(m -> m.connected()).count();
        state = members.stream().anyMatch(m -> !m.connected() || List.of("blocked", "paused", "stopped").contains(m.phase())) ? "Attention" : "Healthy";
        var result = new ArrayList<String>();
        result.add(connected + " / " + members.size() + " connected · " + view.phase());
        for (var member : members) {
            String freshness = member.connected() ? "live" : "stale";
            result.add(member.name() + " · " + member.phase() + " · " + freshness);
        }
        if (!state.equals("Healthy")) result.add(view.detail());
        return result;
    }

    private List<String> supplies(HighwayBuilder highway, Bots bots) {
        state = highway.hasJob() && !highway.isHudHealthy() ? "Attention" : "Observed";
        var result = new ArrayList<>(List.of(highway.getSuppliesSummary().split("\\n")));
        if (bots != null && bots.isActive() && bots.controlCrew().inspect().assigned())
            result.add(bots.controlCrew().inspect().supply().lines().findFirst().orElse("Crew supplies unavailable"));
        result.add("Observed " + LocalTime.now().format(CLOCK));
        return result;
    }

    private List<String> navigation(HighwayBuilder highway) {
        state = "Live";
        double speed = Utils.getPlayerSpeed().horizontalDistance();
        var result = new ArrayList<String>();
        result.add("%d, %d, %d · %s".formatted(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), mc.player.getDirection().getName()));
        result.add(String.format(Locale.ROOT, "%.1f blocks/sec", speed));
        if (highway.hasJob()) result.add(highway.getPlanSummary());
        return result;
    }

    private List<String> notifications() {
        var history = Notifications.FEED.history();
        if (history.isEmpty()) { state = "Quiet"; return List.of("No recent alerts"); }
        var result = new ArrayList<String>();
        boolean warning = false;
        for (var notice : history.reversed()) {
            warning |= notice.severity() == NotificationFeed.Severity.Warning || notice.severity() == NotificationFeed.Severity.Error;
            result.add(notice.source() + (notice.count() > 1 ? " ×" + notice.count() : "") + " · " + notice.text());
            if (result.size() == maximumLines.get()) break;
        }
        state = warning ? "Attention" : "Recent";
        return result;
    }

    @Override public void render(HudRenderer renderer) {
        double s = scale.get(), line = renderer.textHeight(false, s) + 3 * s;
        if (background.get()) {
            renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());
            renderer.quad(x, y, 2 * s, getHeight(), accent());
        }
        renderer.text(panel.get() + " · " + state, x + 8 * s, y + 5 * s, accent(), false, s);
        double ty = y + line + 6 * s;
        for (String text : lines) {
            renderer.text(fit(renderer, text, getWidth() - 16 * s, s), x + 8 * s, ty, TextHud.getSectionColor(1), false, s);
            ty += line;
        }
    }

    private Color accent() {
        if (state.equals("Healthy") || state.equals("Live") || state.equals("Observed") || state.equals("Quiet")) return new Color(128, 212, 173);
        if (state.equals("Attention")) return new Color(241, 189, 98);
        return new Color(224, 189, 115);
    }

    private static String fit(HudRenderer renderer, String text, double width, double scale) {
        text = NotificationFeed.plain(text, 512).replace('\n', ' ');
        if (renderer.textWidth(text, false, scale) <= width) return text;
        int end = text.length();
        while (end > 1 && renderer.textWidth(text.substring(0, end) + "…", false, scale) > width)
            end = text.offsetByCodePoints(0, text.codePointCount(0, end) - 1);
        return text.substring(0, end) + "…";
    }
}
