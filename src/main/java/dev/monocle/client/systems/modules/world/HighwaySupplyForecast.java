package dev.monocle.client.systems.modules.world;

/** Advisory session averages. Constant memory; inventory transfers are sampled out, never trusted as progress. */
public final class HighwaySupplyForecast {
    public static final double UNKNOWN = -1, SUSTAINABLE = -2;
    private double lastTime = -1, seconds, toolSeconds, blockLoss, toolBurn, tripSeconds = 20;
    private int lastBlocks, lastTools, lastToolCount;

    public void sample(double now, boolean working, int blocks, int tools, int toolCount) {
        if (!working) { lastTime = -1; return; }
        double elapsed = now - lastTime;
        if (lastTime >= 0 && elapsed > 0 && elapsed <= 2.5) {
            seconds += elapsed;
            blockLoss += lastBlocks - blocks; // Pickups offset placements, including net-growing paving inventories.
            if (lastToolCount == toolCount) {
                toolSeconds += elapsed;
                toolBurn += Math.max(0, lastTools - tools); // Mending/new tools must not erase observed durability burn.
            }
        }
        lastTime = now; lastBlocks = blocks; lastTools = tools; lastToolCount = toolCount;
    }

    public double pavingSeconds(int available) {
        return seconds < 10 ? UNKNOWN : blockLoss <= 0 ? SUSTAINABLE : Math.max(0, available) * seconds / blockLoss;
    }

    public double pickaxeSeconds(int available) {
        return toolSeconds < 10 || toolBurn <= 0 ? UNKNOWN : Math.max(0, available) * toolSeconds / toolBurn;
    }

    public void supplied(double seconds) {
        if (Double.isFinite(seconds) && seconds > 0) tripSeconds = .75 * tripSeconds + .25 * Math.min(300, seconds);
    }

    public double leadSeconds() { return Math.clamp(tripSeconds * 1.25 + 5, 15, 120); }

    public static boolean due(double eta, double lead) { return Double.isFinite(eta) && eta >= 0 && eta <= lead; }

    public static String describe(double seconds) {
        if (seconds == SUSTAINABLE) return "no net depletion";
        if (!Double.isFinite(seconds) || seconds < 0) return "learning";
        if (seconds < 60) return "~" + (long) Math.ceil(seconds) + "s";
        if (seconds < 3600) return "~" + (long) Math.ceil(seconds / 60) + "m";
        return "~" + (long) Math.ceil(seconds / 3600) + "h";
    }
}
