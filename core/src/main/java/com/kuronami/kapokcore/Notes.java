package com.kuronami.kapokcore;

/**
 * Where this jar writes what it did.
 *
 * <p>Kept as an interface with no loader types so that the activation logic can be
 * unit tested, and so that a missing logging backend degrades to "we say nothing"
 * instead of throwing out of {@code IModFileReader.read}.
 */
public interface Notes {
    /**
     * One line per event that a user must be able to find in latest.log.
     *
     * <p>The caller needs to know whether the line landed. This jar's only evidence that
     * it is running at all is its INFO output, and "no line in the log" has to keep
     * meaning "not running" - so a failure to write is a reason to stop, not something to
     * shrug off.
     *
     * @return true when the message was actually recorded
     */
    boolean info(String message);

    /** True when {@link #detail(String)} would actually be recorded. */
    boolean detailed();

    /** Per-jar bookkeeping. Only interesting when someone is debugging this jar. */
    void detail(String message);

    /**
     * Something went wrong and the feature turned itself off. Reported at INFO:
     * nothing is broken for the user, we simply stopped doing our optimisation.
     */
    void trouble(String message, Throwable cause);
}
