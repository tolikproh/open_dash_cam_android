package com.opendashcam;

import java.util.Calendar;

/**
 * Aligns recording segments to wall-clock boundaries (…:00.000 … …:59.999).
 */
public final class SegmentBoundaryScheduler {
    private SegmentBoundaryScheduler() {
    }

    public static long getSegmentDurationMs(int segmentMinutes) {
        return segmentMinutes * 60_000L;
    }

    /** Inclusive start timestamp of the segment that contains {@code timeMs}. */
    public static long getSegmentStartMs(long timeMs, int segmentMinutes) {
        long segmentMs = getSegmentDurationMs(segmentMinutes);
        long midnightMs = getMidnightMs(timeMs);
        long offsetMs = timeMs - midnightMs;
        return midnightMs + (offsetMs / segmentMs) * segmentMs;
    }

    /** Exclusive end / start of the next segment. */
    public static long getNextBoundaryMs(long timeMs, int segmentMinutes) {
        return getSegmentStartMs(timeMs, segmentMinutes) + getSegmentDurationMs(segmentMinutes);
    }

    public static long getMsUntilNextBoundary(long timeMs, int segmentMinutes) {
        long delay = getNextBoundaryMs(timeMs, segmentMinutes) - timeMs;
        return Math.max(1L, delay);
    }

    private static long getMidnightMs(long timeMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeMs);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
