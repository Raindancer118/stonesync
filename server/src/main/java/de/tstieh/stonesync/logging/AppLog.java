package de.tstieh.stonesync.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single, shared application logger, used everywhere instead of every class minting its own
 * {@code LoggerFactory.getLogger(ThisClass.class)} - one consistent logger name ("stonesync") in
 * every log line, one place to change log formatting/level behavior later.
 */
public final class AppLog {

    private static final Logger LOG = LoggerFactory.getLogger("stonesync");

    private AppLog() {
    }

    public static void info(String format, Object... args) {
        LOG.info(format, args);
    }

    public static void warn(String format, Object... args) {
        LOG.warn(format, args);
    }

    public static void error(String format, Object... args) {
        LOG.error(format, args);
    }

    public static void debug(String format, Object... args) {
        LOG.debug(format, args);
    }
}
