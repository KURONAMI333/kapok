package com.kuronami.kapokcore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shipping {@link Notes}.
 *
 * <p>Every method swallows every throwable. This is the one place in this jar where
 * that is correct: we run inside the mod loader's discovery loop, and a logging backend
 * that cannot be reached from the SERVICE module layer must never be able to stop
 * somebody's game from starting.
 *
 * <p>Swallowing is not the same as ignoring. {@link #info(String)} reports whether the
 * line landed, and {@link Activation} seals the feature when the activation line does
 * not, so a silent log always means a silent jar.
 *
 * <p>The logger is fetched lazily rather than held in a static initialiser, because
 * {@code ServiceLoaderUtil} only catches {@code ServiceConfigurationError} when it
 * constructs providers - an {@code ExceptionInInitializerError} from a static field
 * would go straight through {@code ModDiscoverer}'s constructor.
 */
public final class Slf4jNotes implements Notes {
    private Logger logger;

    private Logger logger() {
        Logger current = this.logger;
        if (current == null) {
            current = LoggerFactory.getLogger("KapokCore");
            this.logger = current;
        }
        return current;
    }

    @Override
    public boolean info(String message) {
        try {
            logger().info(message);
            return true;
        } catch (Throwable ignored) {
            // Swallowed so a broken logging backend cannot stop a launch, but reported
            // upwards so the caller can shut the feature down instead of running blind.
            return false;
        }
    }

    @Override
    public boolean detailed() {
        try {
            return logger().isDebugEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void detail(String message) {
        try {
            logger().debug(message);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void trouble(String message, Throwable cause) {
        try {
            logger().info(message, cause);
        } catch (Throwable ignored) {
        }
    }
}
