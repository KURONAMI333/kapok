package com.kuronami.kapok.mixin.audit;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Where the audit gets a class from, and where that class came from.
 *
 * <p>The origin is carried alongside the node because it is the part a reader
 * has to be able to check: a target that resolves out of some stray jar rather
 * than the merged NeoForge artifact would pass every other test in the audit
 * while proving nothing about the build.
 */
interface ClassLookup {

    /** @return the class and where it was read from, or null if no source has it. */
    Found find(String internalName);

    record Found(ClassNode node, String origin) {}

    /**
     * Reads classes out of an ordered list of jars and directories, first match
     * winning, which is how javac reads the same list.
     *
     * <p>Jars are opened once and asked by name. The merged artifact holds
     * around ten thousand classes and the audit wants around forty of them, so
     * nothing here ever walks a jar's entries.
     */
    final class OnPath implements ClassLookup, Closeable {

        private final List<Path> entries;
        private final Map<Path, ZipFile> jars = new LinkedHashMap<>();
        private final Map<String, Found> cache = new HashMap<>();

        OnPath(List<Path> entries) {
            this.entries = List.copyOf(entries);
        }

        static OnPath of(String pathSeparatedList) {
            List<Path> out = new ArrayList<>();
            for (String piece : pathSeparatedList.split(java.io.File.pathSeparator)) {
                if (!piece.isBlank()) {
                    out.add(Path.of(piece.trim()));
                }
            }
            return new OnPath(out);
        }

        @Override
        public Found find(String internalName) {
            if (cache.containsKey(internalName)) {
                return cache.get(internalName);
            }
            Found found = read(internalName);
            cache.put(internalName, found);
            return found;
        }

        private Found read(String internalName) {
            String relative = internalName + ".class";
            for (Path entry : entries) {
                try {
                    if (Files.isDirectory(entry)) {
                        Path file = entry.resolve(relative);
                        if (Files.isRegularFile(file)) {
                            return new Found(node(Files.readAllBytes(file)), entry.getFileName().toString());
                        }
                    } else if (Files.isRegularFile(entry)) {
                        ZipFile jar = jars.computeIfAbsent(entry, OnPath::open);
                        if (jar == null) {
                            continue;
                        }
                        ZipEntry zipped = jar.getEntry(relative);
                        if (zipped != null) {
                            try (InputStream in = jar.getInputStream(zipped)) {
                                return new Found(node(in.readAllBytes()), entry.getFileName().toString());
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("cannot read " + relative + " from " + entry, e);
                }
            }
            return null;
        }

        private static ZipFile open(Path path) {
            try {
                return new ZipFile(path.toFile());
            } catch (IOException e) {
                // Not every classpath entry is a zip; a directory that vanished
                // or a stray file is simply not a place this class can be.
                return null;
            }
        }

        /** @return the entries this lookup reads, in the order it reads them. */
        List<Path> entries() {
            return entries;
        }

        @Override
        public void close() throws IOException {
            for (ZipFile jar : jars.values()) {
                if (jar != null) {
                    jar.close();
                }
            }
            jars.clear();
        }

        private static ClassNode node(byte[] bytes) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    /**
     * Classes held in memory, falling back to another lookup.
     *
     * <p>This is what lets the positive controls exist: a mixin that names a
     * method the target does not have can be built as a {@link ClassNode} in the
     * test and handed to the same audit, with no compiled fixture to keep out of
     * the shipped jar.
     */
    final class InMemory implements ClassLookup {

        private final Map<String, ClassNode> nodes = new LinkedHashMap<>();
        private final ClassLookup fallback;

        InMemory(ClassLookup fallback) {
            this.fallback = fallback;
        }

        InMemory add(ClassNode node) {
            nodes.put(node.name, node);
            return this;
        }

        @Override
        public Found find(String internalName) {
            ClassNode node = nodes.get(internalName);
            if (node != null) {
                return new Found(node, "(synthesised)");
            }
            return fallback == null ? null : fallback.find(internalName);
        }
    }
}
