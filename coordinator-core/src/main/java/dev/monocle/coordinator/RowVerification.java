package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Map;
import java.util.function.IntPredicate;

/** Pure verification decisions. The client captures server-resolved rows before calling these. */
public final class RowVerification {
    public static final int WINDOW = 5;
    private RowVerification() { }

    public record Progress(int row, boolean currentResolved, Integer base, int mask) {
        public Progress {
            if (row < 0 || base != null && base < 0 || mask < 0 || mask >= 1 << WINDOW)
                throw new IllegalArgumentException("Invalid row observation");
        }
        /** Only use reports already authenticated, generation/scope checked and freshness checked. */
        public static Progress fromReport(JsonObject report) {
            return new Progress(report.get("currentRow").getAsBigDecimal().intValueExact(),
                report.has("currentResolved") && report.get("currentResolved").getAsBoolean(),
                report.has("verifiedBase") ? report.get("verifiedBase").getAsBigDecimal().intValueExact() : null,
                report.has("verifiedMask") ? report.get("verifiedMask").getAsBigDecimal().intValueExact() : 0);
        }
    }

    public static boolean verifiedRow(int base, int mask, int row) {
        long offset = (long) row - base;
        return offset >= 0 && offset < WINDOW && (mask & 1 << (int) offset) != 0;
    }
    public static int resolvedMask(int base, int limit, IntPredicate resolved) {
        int mask = 0;
        for (int offset = 0; offset < WINDOW && (long) base + offset <= limit; offset++)
            if (resolved.test(base + offset)) mask |= 1 << offset;
        return mask;
    }

    /** Empty/missing host rows remain unverified; worker claims cannot override host authority. */
    public static int mask(boolean hostAuthority, int base, int limit, Map<Integer, Boolean> hostRows, Progress worker) {
        return resolvedMask(base, limit, row -> hostAuthority ? Boolean.TRUE.equals(hostRows.get(row))
            : worker != null && worker.base != null && worker.base == base && verifiedRow(base, worker.mask, row));
    }

    public static int checkpoint(boolean hostAuthority, int start, int previous, int minimum,
                                 Map<Integer, Boolean> hostRows, Collection<Progress> workers) {
        boolean resolved = minimum == start || (hostAuthority ? Boolean.TRUE.equals(hostRows.get(minimum))
            : workers.stream().anyMatch(worker -> worker.row == minimum && worker.currentResolved));
        return resolved ? minimum : Math.max(start, Math.min(previous, minimum - 1));
    }
}
