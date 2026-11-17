package com.kuronami.kapok.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

/**
 * The per-level queue: what is held, how it ages, and how many come back per
 * tick. Everything here is plain Java.
 */
class ChunkPostProcessQueueTest {

    private static final int CAPACITY = 3;
    private static final int MAX_AGE = 5;

    private static final BiPredicate<String, Integer> NEVER = (item, age) -> false;
    private static final BiPredicate<String, Integer> ALWAYS = (item, age) -> true;

    private final ChunkPostProcessQueue<String> queue = new ChunkPostProcessQueue<>(CAPACITY, MAX_AGE);

    @Test
    void keepsInsertionOrderAndDoesNotRehold() {
        assertTrue(queue.hold("a"));
        assertTrue(queue.hold("b"));
        assertFalse(queue.hold("a"));
        assertEquals(2, queue.size());
        assertEquals(0, queue.ageOf("a"));
    }

    @Test
    void aHeldItemAgesOncePerTickUntilReady() {
        queue.hold("a");
        assertEquals(List.of(), queue.takeDue(NEVER, 16));
        assertEquals(1, queue.ageOf("a"));
        assertEquals(List.of("a"), queue.takeDue(ALWAYS, 16));
        assertEquals(0, queue.size());
    }

    @Test
    void anItemThatIsNeverReadyIsForcedOutAtTheAgeLimit() {
        queue.hold("a");
        for (int tick = 1; tick < MAX_AGE; tick++) {
            assertEquals(List.of(), queue.takeDue(NEVER, 16), "tick " + tick);
        }
        assertEquals(List.of("a"), queue.takeDue(NEVER, 16));
        assertEquals(0, queue.size());
    }

    @Test
    void anItemPastTheAgeLimitStaysWhenAgeDoesNotForceIt() {
        queue.hold("a");
        for (int tick = 1; tick <= MAX_AGE; tick++) {
            assertEquals(List.of(), queue.takeDueIgnoringAge(NEVER, 16), "tick " + tick);
        }
        assertTrue(queue.isHeld("a"), "the age limit must not remove it");
        assertEquals(1, queue.size());
        assertEquals(MAX_AGE, queue.ageOf("a"));
        // The caller's own condition still decides, and only that.
        assertEquals(List.of("a"), queue.takeDueIgnoringAge(ALWAYS, 16));
        assertEquals(0, queue.size());
    }

    @Test
    void theSameItemIsForcedOutByTheAgeLimitWhenAgeIsUsed() {
        queue.hold("a");
        for (int tick = 1; tick <= MAX_AGE; tick++) {
            assertEquals(List.of(), queue.takeDueIgnoringAge(NEVER, 16), "tick " + tick);
        }
        assertTrue(queue.isHeld("a"));
        assertEquals(List.of("a"), queue.takeDue(NEVER, 16));
        assertEquals(0, queue.size());
    }

    @Test
    void ignoresAgeAndKeepsThePerTickCap() {
        queue.hold("a");
        queue.hold("b");
        queue.hold("c");
        for (int tick = 1; tick <= MAX_AGE; tick++) {
            assertEquals(List.of(), queue.takeDueIgnoringAge(NEVER, 16), "tick " + tick);
        }
        assertEquals(List.of("a", "b"), queue.takeDueIgnoringAge(ALWAYS, 2));
        assertEquals(List.of("c"), queue.takeDueIgnoringAge(ALWAYS, 2));
        assertEquals(0, queue.size());
    }

    @Test
    void atMostThePerTickCapComesBack() {
        queue.hold("a");
        queue.hold("b");
        queue.hold("c");
        assertEquals(List.of("a", "b"), queue.takeDue(ALWAYS, 2));
        assertEquals(1, queue.size());
        assertEquals(1, queue.ageOf("c"));
        assertEquals(List.of("c"), queue.takeDue(ALWAYS, 2));
    }

    @Test
    void onlyTheItemsThatAreReadyComeBack() {
        queue.hold("a");
        queue.hold("b");
        assertEquals(List.of("b"), queue.takeDue((item, age) -> item.equals("b"), 16));
        assertEquals(1, queue.size());
        assertTrue(queue.isHeld("a"));
        assertFalse(queue.isHeld("b"));
    }

    @Test
    void roomIsTheCapacityBound() {
        queue.hold("a");
        queue.hold("b");
        queue.hold("c");
        assertFalse(queue.hasRoom());
        queue.takeDue(ALWAYS, 1);
        assertTrue(queue.hasRoom());
    }

    @Test
    void collectsDueItemsOldestFirstWithoutRemovingThem() {
        queue.hold("a");
        queue.hold("b");
        queue.hold("c");
        queue.ageAll();
        assertEquals(List.of("b"), queue.collectDue((item, age) -> item.equals("b"), 16));
        assertEquals(3, queue.size(), "collecting is a read, not a take");
        assertTrue(queue.take("b"));
        assertEquals(2, queue.size());
        assertEquals(List.of(), queue.collectDue((item, age) -> item.equals("b"), 16));
        assertEquals(List.of("a", "c"), queue.collectDue(ALWAYS, 16));
    }

    @Test
    void collectDueStillForcesOutAtTheAgeLimit() {
        queue.hold("a");
        queue.hold("b");
        for (int tick = 1; tick < MAX_AGE; tick++) {
            queue.ageAll();
            assertEquals(List.of(), queue.collectDue(NEVER, 16), "tick " + tick);
        }
        queue.ageAll();
        assertEquals(List.of("a", "b"), queue.collectDue(NEVER, 16), "both are past the age limit");
        assertEquals(2, queue.size(), "still held until the caller takes them");
    }

    @Test
    void collectingIsCappedAndTheRestKeepTheirAge() {
        queue.hold("a");
        queue.hold("b");
        queue.hold("c");
        queue.ageAll();
        assertEquals(List.of("a", "b"), queue.collectDue(ALWAYS, 2));
        assertEquals(3, queue.size());
        assertEquals(1, queue.ageOf("c"));
        assertTrue(queue.take("a"));
        assertTrue(queue.take("b"));
        assertEquals(List.of("c"), queue.collectDue(ALWAYS, 2));
    }

    @Test
    void takeOnlyRemovesAnItemThatIsStillHeld() {
        queue.hold("a");
        assertTrue(queue.take("a"));
        assertFalse(queue.take("a"), "a second take is a no-op");
        assertFalse(queue.take("z"), "an item that was never held");
        assertEquals(0, queue.size());
    }

    @Test
    void collectDueOnAnEmptyQueueIsEmpty() {
        assertEquals(List.of(), queue.collectDue(ALWAYS, 16));
    }

    @Test
    void ageAllAgesOnceWithoutHandingAnythingBack() {
        queue.hold("a");
        queue.ageAll();
        queue.ageAll();
        assertEquals(2, queue.ageOf("a"));
        assertEquals(1, queue.size());
    }

    @Test
    void clearDropsEverything() {
        queue.hold("a");
        queue.clear();
        assertEquals(0, queue.size());
    }

    @Test
    void purgeDropsOnlyTheItemsTheCallerCanNoLongerUse() {
        queue.hold("a");
        queue.hold("b");
        queue.hold("c");
        assertEquals(1, queue.purge(item -> item.equals("b") || item.equals("c")));
        assertEquals(2, queue.size());
        assertFalse(queue.isHeld("a"));
        assertTrue(queue.isHeld("b"));
        assertEquals(List.of("b", "c"), queue.takeDue(ALWAYS, 16));
    }

    @Test
    void aPurgedItemIsNeverHandedBackLater() {
        queue.hold("a");
        assertEquals(1, queue.purge(item -> false));
        assertEquals(List.of(), queue.takeDue(ALWAYS, 16));
        assertEquals(0, queue.size());
    }

    @Test
    void rejectsANonPositiveCapacityOrAge() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkPostProcessQueue<>(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new ChunkPostProcessQueue<>(5, 0));
    }
}
