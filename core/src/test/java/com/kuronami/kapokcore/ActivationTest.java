package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActivationTest {
    @Test
    void aHealthyLaunchTurnsOnAndSaysSoOnce() {
        CountingEnvironment env = CountingEnvironment.healthy();
        RecordingNotes notes = new RecordingNotes();
        Activation activation = new Activation(env, notes);

        assertTrue(activation.active());
        assertTrue(activation.active());
        assertTrue(activation.active());

        assertEquals(Activation.State.ACTIVE, activation.state());
        assertEquals(1, notes.info.size());
        assertTrue(notes.info.get(0).contains("21.1.247"));
        assertTrue(notes.troubles.isEmpty());
    }

    @Test
    void theEnvironmentIsConsultedExactlyOnce() {
        CountingEnvironment env = CountingEnvironment.healthy();
        Activation activation = new Activation(env, new RecordingNotes());

        for (int i = 0; i < 200; i++) {
            activation.active();
        }

        assertEquals(1, env.versionCalls);
        assertEquals(1, env.readerCalls);
    }

    @Test
    void anotherNeoForgeLineSealsIt() {
        RecordingNotes notes = new RecordingNotes();
        Activation activation = new Activation(
                CountingEnvironment.of("21.2.1", ReaderCensus.OURS), notes);

        assertFalse(activation.active());
        assertEquals(Activation.State.SEALED, activation.state());
        assertEquals(1, notes.info.size());
        assertTrue(notes.info.get(0).contains("21.2.1"));
    }

    @Test
    void anUnknownVersionSealsItRatherThanGuessing() {
        RecordingNotes notes = new RecordingNotes();
        Activation activation = new Activation(CountingEnvironment.of(null), notes);

        assertFalse(activation.active());
        assertEquals(Activation.State.SEALED, activation.state());
        assertTrue(notes.info.get(0).contains("<unknown>"));
    }

    @Test
    void aStrangerReaderSealsIt() {
        RecordingNotes notes = new RecordingNotes();
        Activation activation = new Activation(CountingEnvironment.of("21.1.247",
                ReaderCensus.BUILT_IN_JAR_MODS_TOML,
                ReaderCensus.OURS,
                "com.example.TheirReader"), notes);

        assertFalse(activation.active());
        assertEquals(Activation.State.SEALED, activation.state());
        assertTrue(notes.info.get(0).contains("com.example.TheirReader"));
    }

    @Test
    void anEnvironmentThatThrowsSealsItAndIsNotAskedAgain() {
        RuntimeException boom = new IllegalStateException("module graph unavailable");
        CountingEnvironment env = CountingEnvironment.failing(boom);
        RecordingNotes notes = new RecordingNotes();
        Activation activation = new Activation(env, notes);

        assertFalse(activation.active());
        assertFalse(activation.active());

        assertEquals(Activation.State.SEALED, activation.state());
        assertEquals(1, env.readerCalls);
        assertEquals(1, notes.troubles.size());
        assertSame(boom, notes.causes.get(0));
    }

    @Test
    void sealingAnActiveInstanceStopsItAndReportsOnce() {
        RecordingNotes notes = new RecordingNotes();
        Activation activation = new Activation(CountingEnvironment.healthy(), notes);
        assertTrue(activation.active());

        RuntimeException boom = new RuntimeException("bad jar");
        activation.seal("an unexpected error", boom);
        activation.seal("an unexpected error", boom);

        assertFalse(activation.active());
        assertEquals(Activation.State.SEALED, activation.state());
        assertEquals(1, notes.troubles.size());
    }

    @Test
    void aCensusThatCannotEvenFindUsSealsIt() {
        RecordingNotes notes = new RecordingNotes();
        Activation activation = new Activation(CountingEnvironment.of("21.1.247",
                ReaderCensus.BUILT_IN_JAR_MODS_TOML,
                ReaderCensus.BUILT_IN_NESTED_LIBRARY), notes);

        assertFalse(activation.active());
        assertEquals(Activation.State.SEALED, activation.state());
        assertTrue(notes.info.get(0).contains("did not find this jar"));
    }

    @Test
    void aHealthyLaunchThatCannotBeAnnouncedSealsInstead() {
        // The activation line is the only evidence this jar produces. If it cannot be
        // written we would be claiming every mod jar in the pack with no way for anyone
        // to tell, so "no line in the log" would stop meaning "not running".
        RecordingNotes notes = new RecordingNotes();
        notes.infoLands = false;
        Activation activation = new Activation(CountingEnvironment.healthy(), notes);

        assertFalse(activation.active());
        assertEquals(Activation.State.SEALED, activation.state());
        assertTrue(notes.info.isEmpty());
    }

    @Test
    void sealingBeforeTheFirstReadNeverConsultsTheEnvironment() {
        CountingEnvironment env = CountingEnvironment.healthy();
        Activation activation = new Activation(env, new RecordingNotes());

        activation.seal("shut down early", new RuntimeException());

        assertFalse(activation.active());
        assertEquals(0, env.versionCalls);
        assertEquals(0, env.readerCalls);
    }
}
