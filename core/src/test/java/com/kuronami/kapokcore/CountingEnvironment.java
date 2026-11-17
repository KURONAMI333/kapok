package com.kuronami.kapokcore;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** A {@link LoaderEnvironment} that records how often it was asked anything. */
final class CountingEnvironment implements LoaderEnvironment {
    private final String version;
    private final Collection<String> readers;
    private final RuntimeException failure;
    int versionCalls;
    int readerCalls;

    private CountingEnvironment(String version, Collection<String> readers, RuntimeException failure) {
        this.version = version;
        this.readers = readers;
        this.failure = failure;
    }

    static CountingEnvironment of(String version, String... readers) {
        return new CountingEnvironment(version, List.of(readers), null);
    }

    static CountingEnvironment failing(RuntimeException failure) {
        return new CountingEnvironment("21.1.247", List.of(), failure);
    }

    /** The set a healthy 21.1.247 launch produces: the two built-ins plus us. */
    static CountingEnvironment healthy() {
        return of("21.1.247",
                ReaderCensus.BUILT_IN_JAR_MODS_TOML,
                ReaderCensus.BUILT_IN_NESTED_LIBRARY,
                ReaderCensus.OURS);
    }

    @Override
    public String neoForgeVersion() {
        versionCalls++;
        return version;
    }

    /** No cache directory: these tests are about activation, not about storing scans. */
    @Override
    public Path gameDirectory() {
        return null;
    }

    @Override
    public Collection<String> modFileReaderClassNames() {
        readerCalls++;
        if (failure != null) {
            throw failure;
        }
        return readers;
    }
}
