package com.kuronami.kapokcore;

import java.util.Collection;
import java.util.List;

/**
 * The self-seal. Decided once, on the first jar we are offered, and never revisited.
 *
 * <p>Placement matters as much as content:
 *
 * <ul>
 *   <li>Not in a constructor. {@code ServiceLoaderUtil} catches only
 *       {@code ServiceConfigurationError} when it builds providers, so a
 *       {@code RuntimeException} raised while constructing this reader would travel
 *       through {@code ModDiscoverer}'s constructor and take the launch with it.
 *   <li>Never thrown out of {@code read}. {@code DiscoveryPipeline#addJarContent} turns
 *       an exception from a reader into {@code fml.modloadingissue.brokenfile} reported
 *       <em>against the jar being read</em>, and {@code readModFile} does not catch at
 *       all. Either way an unrelated mod would be blamed for our bug.
 * </ul>
 *
 * <p>So every failure mode lands here as a state change plus one INFO line, and the
 * reader simply starts returning {@code null}, which is exactly how the loader behaves
 * when this jar is not installed.
 */
public final class Activation {
    public enum State {
        /** Nothing has been checked yet. */
        PENDING,
        /** Checks passed; the reader may claim jars. */
        ACTIVE,
        /** Permanently off for this launch. */
        SEALED
    }

    private final LoaderEnvironment environment;
    private final Notes notes;
    private State state = State.PENDING;

    public Activation(LoaderEnvironment environment, Notes notes) {
        this.environment = environment;
        this.notes = notes;
    }

    /**
     * @return true when the reader may act. The environment is consulted at most once
     *         per launch, whatever it answers and whether or not it throws.
     */
    public synchronized boolean active() {
        if (state == State.PENDING) {
            state = decide();
        }
        return state == State.ACTIVE;
    }

    /** Turns the feature off for good. Idempotent; safe to call from any state. */
    public synchronized void seal(String reason, Throwable cause) {
        if (state == State.SEALED) {
            return;
        }
        state = State.SEALED;
        notes.trouble("Kapok Core is off for this launch: " + reason, cause);
    }

    public synchronized State state() {
        return state;
    }

    private State decide() {
        String version;
        Collection<String> readerNames;
        try {
            version = environment.neoForgeVersion();
            readerNames = environment.modFileReaderClassNames();
        } catch (Throwable t) {
            // Includes ServiceConfigurationError and any linkage error from walking the
            // module graph. We cannot tell who else is here, so we assume someone is.
            notes.trouble("Kapok Core is off for this launch: the loader could not be inspected", t);
            return State.SEALED;
        }

        if (!VersionGate.supports(version)) {
            notes.info("Kapok Core is off for this launch: built for NeoForge "
                    + VersionGate.SUPPORTED_LINE + ".x, found " + VersionGate.describe(version));
            return State.SEALED;
        }

        if (!ReaderCensus.recognisesUs(readerNames)) {
            notes.info("Kapok Core is off for this launch: the reader census did not find this jar"
                    + " itself, so it cannot be trusted to find anyone else");
            return State.SEALED;
        }

        List<String> foreign = ReaderCensus.foreign(readerNames);
        if (!foreign.isEmpty()) {
            notes.info("Kapok Core is off for this launch: another mod also reads mod files ("
                    + String.join(", ", foreign) + ")");
            return State.SEALED;
        }

        boolean announced = notes.info("Kapok Core active on NeoForge " + VersionGate.describe(version)
                + ": mod classes will be read once at startup instead of twice");
        if (!announced) {
            // We cannot be seen. This one line is the only evidence this jar produces -
            // there is no getter for the securityStatus it influences and nothing fires
            // when discovery ends - so running unannounced would mean claiming every mod
            // jar in the pack with no way for anyone to tell we did. Stop instead, and
            // keep "no line in the log" meaning "not running".
            return State.SEALED;
        }
        return State.ACTIVE;
    }
}
