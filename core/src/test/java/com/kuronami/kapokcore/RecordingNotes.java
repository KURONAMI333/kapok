package com.kuronami.kapokcore;

import java.util.ArrayList;
import java.util.List;

/** Captures what would have been written to the log. */
final class RecordingNotes implements Notes {
    final List<String> info = new ArrayList<>();
    final List<String> details = new ArrayList<>();
    final List<String> troubles = new ArrayList<>();
    final List<Throwable> causes = new ArrayList<>();

    /** Set false to simulate a logging backend that cannot be reached. */
    boolean infoLands = true;

    @Override
    public boolean info(String message) {
        if (!infoLands) {
            return false;
        }
        info.add(message);
        return true;
    }

    @Override
    public boolean detailed() {
        return true;
    }

    @Override
    public void detail(String message) {
        details.add(message);
    }

    @Override
    public void trouble(String message, Throwable cause) {
        troubles.add(message);
        causes.add(cause);
    }

    /** Every line the user could see, whatever the level. */
    List<String> everything() {
        List<String> all = new ArrayList<>(info);
        all.addAll(troubles);
        return all;
    }
}
