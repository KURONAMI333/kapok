package com.kuronami.kapok.chunk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * The chunks whose {@code postProcessGeneration} was put off, with the number
 * of ticks each one has waited.
 *
 * <p>Insertion order is kept, so the oldest request is looked at first. A held
 * item leaves the queue only when the caller asks: {@link #takeDue} and
 * {@link #takeDueIgnoringAge} hand it back, {@link #purge} drops the ones whose
 * game-side object is gone, {@link #clear} drops everything. Nothing is removed
 * on its own, so the game-side caller is the one that decides when a chunk is
 * done.
 *
 * <p>{@link #takeDue} also hands an item back once its age reached
 * {@code maxAgeTicks}, whether or not the caller's condition is true, so a
 * chunk cannot wait forever. {@link #takeDueIgnoringAge} leaves that forcing
 * out, for the caller whose condition is not a latency preference but a
 * precondition of the call itself.
 *
 * <p>{@link #ageAll} plus {@link #collectDue} and {@link #take} is the same rule
 * split into the parts a time-limited caller needs: age once per tick, collect
 * the due items in one scan without removing them, then take one at a time as
 * it runs them. A stop in the middle therefore leaves the items it did not reach
 * in the queue with their age and their hold on the send, and the caller does not
 * rescan the queue once per item.

 *
 * <p>Only touched from the server thread; no synchronisation.
 *
 * @param <T> the held item, a chunk in the game
 */
public final class ChunkPostProcessQueue<T> {

    private final Map<T, Integer> held = new LinkedHashMap<>();

    private final int maxAgeTicks;
    private final int capacity;

    /**
     * @param capacity     largest number of items kept; the caller stops adding
     *                     before this
     * @param maxAgeTicks  age at which an item is handed back even when the
     *                     caller's condition has never been true
     */
    public ChunkPostProcessQueue(int capacity, int maxAgeTicks) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (maxAgeTicks <= 0) {
            throw new IllegalArgumentException("maxAgeTicks must be positive: " + maxAgeTicks);
        }
        this.capacity = capacity;
        this.maxAgeTicks = maxAgeTicks;
    }

    /**
     * @return {@code false} when the item was already held. The original entry
     *         and its age are kept: re-holding must not make it wait longer.
     */
    public boolean hold(T item) {
        if (held.containsKey(item)) {
            return false;
        }
        held.put(item, 0);
        return true;
    }

    public boolean isHeld(T item) {
        return held.containsKey(item);
    }

    public int size() {
        return held.size();
    }

    public boolean hasRoom() {
        return held.size() < this.capacity;
    }

    public int ageOf(T item) {
        return held.getOrDefault(item, -1);
    }

    /**
     * Ages every held item by one tick and returns at most {@code max} of them.
     *
     * <p>An item is handed back when {@code runNow} is true for it or when its
     * age reached {@code maxAgeTicks}; see {@link #takeDueIgnoringAge} for the
     * entry point that does not force by age.
     *
     * @param runNow decides from the item and its new age whether to hand it
     *               back; it is called once per held item, oldest first, and the
     *               scan stops as soon as {@code max} items were taken
     * @return the items to run, already removed from the queue
     */
    public List<T> takeDue(BiPredicate<T, Integer> runNow, int max) {
        return takeDue(runNow, max, true);
    }

    /**
     * Ages every held item by one tick and returns at most {@code max} of them,
     * deciding by {@code runNow} alone: the age limit never forces an item out.
     *
     * <p>For a caller whose condition is a precondition of the work (the call
     * is wrong, not merely late, without it), forcing by age would do the wrong
     * thing. Age still advances, so a later {@link #takeDue} on the same item
     * sees the same age it would have had.
     *
     * @param runNow decides from the item and its new age whether to hand it
     *               back; it is called once per held item, oldest first, and the
     *               scan stops as soon as {@code max} items were taken
     * @return the items to run, already removed from the queue
     */
    public List<T> takeDueIgnoringAge(BiPredicate<T, Integer> runNow, int max) {
        return takeDue(runNow, max, false);
    }

    private List<T> takeDue(BiPredicate<T, Integer> runNow, int max, boolean forceAtMaxAge) {
        if (held.isEmpty() || max <= 0) {
            return List.of();
        }
        ageAll();
        List<T> due = new ArrayList<>();
        Iterator<Map.Entry<T, Integer>> iterator = held.entrySet().iterator();
        while (iterator.hasNext() && due.size() < max) {
            Map.Entry<T, Integer> entry = iterator.next();
            if ((forceAtMaxAge && entry.getValue() >= this.maxAgeTicks)
                    || runNow.test(entry.getKey(), entry.getValue())) {
                due.add(entry.getKey());
                iterator.remove();
            }
        }
        return due;
    }

    /** Ages every held item by one tick, without handing anything back. */
    public void ageAll() {
        for (Map.Entry<T, Integer> entry : held.entrySet()) {
            entry.setValue(entry.getValue() + 1);
        }
    }

    /**
     * Ages nothing itself and hands back up to {@code max} due items, oldest
     * first, without removing them. Nothing changes in the queue, so a caller
     * that runs only some of them can stop and leave the rest exactly as they
     * were, age included.
     *
     * <p>For a caller that stops on a time limit: it collects once per tick,
     * then {@link #take}s each item as it runs it.
     *
     * <p>Like {@link #takeDue}, an item is due when {@code runNow} is true for it
     * or when its age reached {@code maxAgeTicks}.
     *
     * @param runNow decides from the item and its current age whether it is due;
     *               called at most once per held item, oldest first, and the scan
     *               stops as soon as {@code max} items were collected
     * @param max    most items to collect
     * @return the due items, in queue order, still held
     */
    public List<T> collectDue(BiPredicate<T, Integer> runNow, int max) {
        if (held.isEmpty() || max <= 0) {
            return List.of();
        }
        List<T> due = new ArrayList<>();
        for (Map.Entry<T, Integer> entry : held.entrySet()) {
            if (due.size() >= max) {
                break;
            }
            if (entry.getValue() >= this.maxAgeTicks || runNow.test(entry.getKey(), entry.getValue())) {
                due.add(entry.getKey());
            }
        }
        return due;
    }

    /**
     * Removes one item that {@link #collectDue} handed back, for a caller that is
     * about to run it now.
     *
     * @return {@code false} when the item had already left the queue
     */
    public boolean take(T item) {
        return held.remove(item) != null;
    }

    public void clear() {
        held.clear();
    }

    /**
     * Drops every item the caller can no longer use, without handing it back.
     *
     * <p>Used for an item whose game-side object was replaced or removed (a
     * chunk that unloaded and was loaded again as a new instance): running the
     * old one would apply its changes through the level, which can pull the
     * chunk back in.
     *
     * @param keep decides from the item whether to keep it; called once per
     *             held item, oldest first
     * @return how many items were dropped
     */
    public int purge(Predicate<T> keep) {
        int dropped = 0;
        Iterator<Map.Entry<T, Integer>> iterator = held.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!keep.test(iterator.next().getKey())) {
                iterator.remove();
                dropped++;
            }
        }
        return dropped;
    }
}
