package com.kuronami.kapok.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameIntervalProbeTest {
    @Test
    void warmupBoundaryStartsANewIntervalSequence() {
        FrameIntervalProbe probe = new FrameIntervalProbe(2_000L, 8);
        probe.join(1_000L);

        probe.frame(1_100L);
        probe.frame(2_999L);
        probe.frame(3_000L);
        probe.frame(3_010L);

        FrameIntervalProbe.Summary summary = probe.finish().orElseThrow();
        assertEquals(1L, summary.count());
        assertEquals(10L, summary.totalNanos());
        assertEquals(10L, summary.p50Nanos());
    }

    @Test
    void framesOutsideAJoinedWorldAreExcludedAndRejoinResetsTheRun() {
        FrameIntervalProbe probe = new FrameIntervalProbe(0L, 8);
        probe.frame(10L);

        probe.join(20L);
        probe.frame(25L);
        probe.frame(35L);
        assertEquals(1L, probe.finish().orElseThrow().count());
        assertTrue(probe.finish().isEmpty(), "one join is reported once");
        probe.frame(45L);

        probe.join(100L);
        probe.frame(110L);
        probe.frame(140L);
        FrameIntervalProbe.Summary second = probe.finish().orElseThrow();
        assertEquals(1L, second.count());
        assertEquals(30L, second.totalNanos());
    }

    @Test
    void overflowKeepsCountingDurationAndMaximumWithoutGrowingStorage() {
        FrameIntervalProbe probe = new FrameIntervalProbe(0L, 2);
        probe.join(0L);
        probe.frame(10L);
        probe.frame(20L);
        probe.frame(40L);
        probe.frame(70L);

        FrameIntervalProbe.Summary summary = probe.finish().orElseThrow();
        assertEquals(3L, summary.count());
        assertEquals(2, summary.retained());
        assertEquals(1L, summary.overflow());
        assertEquals(60L, summary.totalNanos());
        assertEquals(30L, summary.maxNanos());
    }

    @Test
    void percentilesUseNearestRankOverTheRetainedIntervals() {
        FrameIntervalProbe probe = new FrameIntervalProbe(0L, 8);
        probe.join(0L);
        probe.frame(0L);
        probe.frame(1L);
        probe.frame(3L);
        probe.frame(6L);
        probe.frame(10L);
        probe.frame(110L);

        FrameIntervalProbe.Summary summary = probe.finish().orElseThrow();
        assertEquals(5L, summary.count());
        assertEquals(3L, summary.p50Nanos());
        assertEquals(100L, summary.p95Nanos());
        assertEquals(100L, summary.p99Nanos());
        assertEquals(100L, summary.maxNanos());
        assertEquals(110L, summary.totalNanos());
    }

    @Test
    void aJoinWithNoCompleteIntervalStillProducesAnExplicitEmptySummary() {
        FrameIntervalProbe probe = new FrameIntervalProbe(20L, 1);
        probe.join(5L);
        probe.frame(25L);

        FrameIntervalProbe.Summary summary = probe.finish().orElseThrow();
        assertEquals(0L, summary.count());
        assertEquals(0L, summary.p50Nanos());
        assertEquals(0L, summary.p95Nanos());
        assertEquals(0L, summary.p99Nanos());
        assertEquals(0L, summary.maxNanos());
        assertEquals(0L, summary.totalNanos());
    }
}
