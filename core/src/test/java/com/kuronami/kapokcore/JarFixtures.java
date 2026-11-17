package com.kuronami.kapokcore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Real jar files on disk, so tests can drive the loader's real jar handling. */
final class JarFixtures {
    /**
     * Directory of real, signed mod jars to sweep. Read from the environment rather than
     * hardcoded: the path is a local mod instance, and this source is published.
     */
    static final String SIGNED_JAR_DIR_ENV = "KAPOKCORE_SIGNED_JAR_DIR";

    private JarFixtures() {
    }

    /** An unsigned jar with a realistic number of class entries at varying depths. */
    static Path unsigned(Path dir, String name, int classCount) throws IOException {
        Path jar = dir.resolve(name);
        Random random = new Random(name.hashCode());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (int i = 0; i < classCount; i++) {
                out.putNextEntry(new ZipEntry("com/example/pkg" + (i % 7) + "/C" + i + ".class"));
                byte[] body = new byte[16 + random.nextInt(500)];
                random.nextBytes(body);
                out.write(body);
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("assets/example/lang/en_us.json"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    /**
     * A jar with exactly the entries given, written in exactly the order given.
     *
     * <p>Order is a parameter because the cache key has to be insensitive to it: two
     * builds of the same sources may emit the same files in a different sequence, and a
     * key that noticed would throw the cache away for nothing.
     */
    static Path jarOf(Path dir, String name, LinkedHashMap<String, byte[]> entries) throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    /** The entries of {@link #jarOf}'s usual subject: a manifest, three classes, a resource. */
    static LinkedHashMap<String, byte[]> entries() {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/A.class", "class A body".getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/B.class", "class B body".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/versions/17/com/example/A.class", "class A17".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/example/lang/en_us.json", "{}".getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    /** The same entries, written back to front. */
    static LinkedHashMap<String, byte[]> reversed(LinkedHashMap<String, byte[]> entries) {
        List<String> names = new ArrayList<>(entries.keySet());
        Collections.reverse(names);
        LinkedHashMap<String, byte[]> flipped = new LinkedHashMap<>();
        for (String each : names) {
            flipped.put(each, entries.get(each));
        }
        return flipped;
    }

    /**
     * A jar carrying other jars, the way jar-in-jar ships a mod's dependencies.
     *
     * @param nested entry name to jar file, so a test can choose where inside the jar each
     *               one goes - {@code META-INF/jarjar/} and {@code META-INF/jars/} are
     *               both in use in the wild, and nothing may assume either
     */
    static Path carrying(Path dir, String name, LinkedHashMap<String, Path> nested)
            throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("com/example/Outer.class"));
            out.write("class Outer body".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (var entry : nested.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(Files.readAllBytes(entry.getValue()));
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Uncompressed size of an entry as the central directory records it. */
    static long sizeOf(Path jar, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("no entry " + entryName + " in " + jar);
            }
            return entry.getSize();
        }
    }

    /** CRC-32 of an entry as the central directory records it. */
    static long crcOf(Path jar, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("no entry " + entryName + " in " + jar);
            }
            return entry.getCrc();
        }
    }

    /**
     * Copies every signed jar out of {@link #SIGNED_JAR_DIR_ENV} into {@code into}. The
     * source directory is only ever read.
     *
     * @return the copies, or an empty list when the variable is unset or the directory is
     *         gone - the caller skips rather than fails, because these are real mod jars
     *         from a local instance and cannot be committed
     */
    static List<Path> copySignedJars(Path into) throws IOException {
        String configured = System.getenv(SIGNED_JAR_DIR_ENV);
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        Path source = Path.of(configured);
        if (!Files.isDirectory(source)) {
            return List.of();
        }
        List<Path> copies = new ArrayList<>();
        try (var listing = Files.list(source)) {
            for (Path candidate : listing.toList()) {
                if (!candidate.getFileName().toString().endsWith(".jar") || !isSigned(candidate)) {
                    continue;
                }
                Path copy = into.resolve(candidate.getFileName().toString());
                Files.copy(candidate, copy);
                copies.add(copy);
            }
        }
        return copies;
    }

    /** True when the jar carries a PKCS#7 signature block under META-INF. */
    static boolean isSigned(Path jar) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName().toUpperCase(Locale.ROOT);
                if (name.startsWith("META-INF/") && name.indexOf('/', 9) < 0
                        && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
