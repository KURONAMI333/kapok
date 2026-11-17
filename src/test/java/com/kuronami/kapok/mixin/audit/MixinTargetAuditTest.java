package com.kuronami.kapok.mixin.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Holds the build to what the mixins claim.
 *
 * <p>The annotation processor is not running here, so nothing between writing a
 * mixin and starting a game had any opinion about whether the methods, fields
 * and instructions it names exist. {@link MixinAudit} does, and this runs it
 * over the configs the mod ships, against the same classpath javac
 * compiled the mixins against.
 *
 * <p>The second test is the control. A checker that has quietly stopped looking
 * at anything passes the first test perfectly, so mixins with known bad targets
 * are built here as bytecode and handed to the same audit, which has to call
 * every one of them out — and has to leave the good one alone.
 */
class MixinTargetAuditTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String CHUNK_MAP = "net/minecraft/server/level/ChunkMap";
    private static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
    private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String RETURN_CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    /**
     * How many of each kind the audit has to have looked at for a green to mean
     * anything. A parsing mistake that makes a whole category come out empty
     * would otherwise pass this test perfectly while checking nothing.
     *
     * <p>These are hand set well below what the mod currently has, so that
     * removing a mixin does not fail the test for the wrong reason. Adding a
     * feature that brings a lot of one kind is a good moment to raise them.
     */
    private static final Map<String, Integer> FLOORS = Map.of(
            "@Inject", 4,
            "@WrapMethod", 4,
            "@WrapOperation", 1,
            "@Shadow", 4,
            "@Accessor", 1,
            "@Invoker", 1,
            "@At", 3);

    @Test
    void everyTargetTheShippedMixinsNameIsThere() throws Exception {
        List<MixinAudit.ConfigSpec> configs = shippedConfigs();
        assertEquals(2, configs.size(), "the mod ships two mixin configs: kapok-chunk and kapok-map");

        try (ClassLookup.OnPath targets = compileClasspath()) {
            List<MixinAudit.Finding> findings = new MixinAudit(mixinClasses(), targets).run(configs);
            String report = report(findings, configs);
            System.out.println(report);
            writeReport(report);

            Map<String, Integer> seen = countByCategory(findings);
            for (Map.Entry<String, Integer> floor : new TreeMap<>(FLOORS).entrySet()) {
                int count = seen.getOrDefault(floor.getKey(), 0);
                assertTrue(count >= floor.getValue(), () -> "the audit only looked at " + count + " "
                        + floor.getKey() + " item(s); it used to see at least " + floor.getValue()
                        + ", so something has stopped being read rather than started being right");
            }
            // Counted from the configs rather than written down, so a wave that
            // adds or drops a mixin does not have to come and edit a number here.
            int declared = configs.stream().mapToInt(c -> c.mixins().size()).sum();
            assertEquals(declared, findings.stream().map(MixinAudit.Finding::mixin).distinct().count(),
                    "every mixin class named by the configs has to be audited");
            assertTrue(seen.getOrDefault("@Mixin", 0) >= declared,
                    "every mixin class has at least one target, so there is at least one @Mixin item each");

            List<String> bad = findings.stream().filter(f -> !f.ok()).map(MixinAudit.Finding::toString).toList();
            assertTrue(bad.isEmpty(), () -> bad.size() + " mixin item(s) name something that is not there:\n"
                    + String.join("\n", bad));
        }
    }

    @Test
    void theAuditSaysNoWhenTheTargetIsNotThere() throws Exception {
        try (ClassLookup.OnPath targets = compileClasspath()) {
            ClassLookup.InMemory mixins = new ClassLookup.InMemory(null)
                    .add(missingMethod())
                    .add(shadowOfAnInheritedField())
                    .add(wrongDescriptorOnTheInstruction())
                    .add(wrongCallbackForVoidTarget())
                    .add(missingField())
                    .add(missingTargetClass())
                    .add(soundOne())
                    .add(bareNames());

            MixinAudit.ConfigSpec config = new MixinAudit.ConfigSpec("(control)", "control",
                    List.of("MissingMethod", "ShadowOfAnInheritedField", "WrongDescriptor", "WrongCallback",
                            "MissingField", "MissingTargetClass", "SoundOne", "BareNames"),
                    1);

            List<MixinAudit.Finding> findings = new MixinAudit(mixins, targets).run(List.of(config));
            System.out.println(report(findings, List.of(config)));

            assertNg(findings, "MissingMethod", "the target declares no such method");
            assertNg(findings, "ShadowOfAnInheritedField", "which @Shadow does not reach");
            assertNg(findings, "WrongDescriptor", "no instruction in the method body matches");
            assertNg(findings, "WrongCallback", "callback type must be");
            assertNg(findings, "MissingField", "no such field on the target");
            assertNg(findings, "MissingTargetClass", "no class by that name");

            assertOk(findings, "SoundOne");
            assertOk(findings, "BareNames");
        }
    }

    private static void assertOk(List<MixinAudit.Finding> findings, String mixin) {
        List<MixinAudit.Finding> mine = findings.stream().filter(f -> f.mixin().equals(mixin)).toList();
        assertFalse(mine.isEmpty(), () -> mixin + " produced no findings at all");
        assertTrue(mine.stream().allMatch(MixinAudit.Finding::ok),
                () -> "the audit failed " + mixin + ", which is correct:\n" + mine.stream()
                        .filter(f -> !f.ok()).map(Object::toString).reduce("", (a, b) -> a + b + "\n"));
    }

    private static void assertNg(List<MixinAudit.Finding> findings, String mixin, String because) {
        boolean caught = findings.stream()
                .anyMatch(f -> f.mixin().equals(mixin) && !f.ok() && f.detail().contains(because));
        assertTrue(caught, () -> "the audit passed " + mixin + ", which it must not:\n"
                + findings.stream().filter(f -> f.mixin().equals(mixin))
                        .map(Object::toString).reduce("", (a, b) -> a + b + "\n"));
    }

    // ------------------------------------------------------------- controls

    /** Injects into a method {@code ChunkMap} does not have. */
    private static ClassNode missingMethod() {
        ClassNode node = shell("control/MissingMethod", mixinOf(CHUNK_MAP));
        node.methods.add(injector("onNothing", "noSuchMethod()V", at("HEAD", null)));
        return node;
    }

    /**
     * Shadows {@code chunkPos}, which {@code LevelChunk} inherits from
     * {@code ChunkAccess} rather than declaring — the ninth wave's defect, in the
     * smallest form that still has it.
     */
    private static ClassNode shadowOfAnInheritedField() {
        ClassNode node = shell("control/ShadowOfAnInheritedField", mixinOf(LEVEL_CHUNK));
        FieldNode field = new FieldNode(Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL,
                "chunkPos", "Lnet/minecraft/world/level/ChunkPos;", null, null);
        field.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(SHADOW)));
        node.fields.add(field);
        return node;
    }

    /**
     * Names a call that is in {@code processUnloads}, with the return type it
     * does not have. The real mixin wraps {@code ObjectCollection.iterator}
     * returning {@code ObjectIterator}; this asks for {@code java.util.Iterator}.
     */
    private static ClassNode wrongDescriptorOnTheInstruction() {
        ClassNode node = shell("control/WrongDescriptor", mixinOf(CHUNK_MAP));
        node.methods.add(injector("onIterator", "processUnloads(Ljava/util/function/BooleanSupplier;)V",
                at("INVOKE", "Lit/unimi/dsi/fastutil/objects/ObjectCollection;iterator()Ljava/util/Iterator;")));
        return node;
    }

    /** A non-void callback on a void target is rejected by Mixin at game start. */
    private static ClassNode wrongCallbackForVoidTarget() {
        ClassNode node = shell("control/WrongCallback", mixinOf(CHUNK_MAP));
        MethodNode handler = injector("onTick", "tick(Ljava/util/function/BooleanSupplier;)V", at("HEAD", null));
        handler.desc = "(" + RETURN_CALLBACK + ")V";
        node.methods.add(handler);
        return node;
    }

    /** Accesses a field {@code ChunkMap} does not have. */
    private static ClassNode missingField() {
        ClassNode node = shell("control/MissingField", mixinOf(CHUNK_MAP));
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getNoSuchField", "()I", null, null);
        AnnotationNode accessor = new AnnotationNode(ACCESSOR);
        accessor.values = new ArrayList<>(List.of("value", "noSuchField"));
        method.visibleAnnotations = new ArrayList<>(List.of(accessor));
        node.methods.add(method);
        return node;
    }

    /** Aims at a class that is on no artifact of this build. */
    private static ClassNode missingTargetClass() {
        AnnotationNode mixin = new AnnotationNode(MIXIN);
        mixin.values = new ArrayList<>(List.of("targets", new ArrayList<>(List.of("net.minecraft.no.Such"))));
        return shell("control/MissingTargetClass", mixin);
    }

    /** Everything about this one is true, and the audit has to leave it alone. */
    private static ClassNode soundOne() {
        ClassNode node = shell("control/SoundOne", mixinOf(CHUNK_MAP));
        node.methods.add(injector("onTick", "tick(Ljava/util/function/BooleanSupplier;)V", at("TAIL", null)));
        node.methods.add(injector("onIterator", "processUnloads(Ljava/util/function/BooleanSupplier;)V",
                at("INVOKE", "Lit/unimi/dsi/fastutil/objects/ObjectCollection;"
                        + "iterator()Lit/unimi/dsi/fastutil/objects/ObjectIterator;")));
        FieldNode field = new FieldNode(Opcodes.ACC_PRIVATE, "visibleChunkMap",
                "Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;", null, null);
        field.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(SHADOW)));
        node.fields.add(field);
        return node;
    }

    /**
     * Leaves both names to be worked out from the method's own.
     *
     * <p>Every accessor and invoker in the mod writes its target out, so nothing
     * in the shipped configs reaches the part of the audit that strips
     * {@code get}/{@code set}/{@code is} and {@code call}/{@code invoke} — and an
     * inference that is wrong would fail a mixin that is fine, which is the shape
     * of mistake that gets a check loosened rather than fixed.
     */
    private static ClassNode bareNames() {
        ClassNode node = shell("control/BareNames", mixinOf(CHUNK_MAP));
        MethodNode accessor = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getVisibleChunkMap", "()Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;", null, null);
        accessor.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(ACCESSOR)));
        node.methods.add(accessor);

        MethodNode invoker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "invokeSaveChunkIfNeeded", "(Lnet/minecraft/server/level/ChunkHolder;)Z", null, null);
        invoker.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(INVOKER)));
        node.methods.add(invoker);
        return node;
    }

    private static ClassNode shell(String name, AnnotationNode mixin) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
        node.name = name;
        node.superName = "java/lang/Object";
        node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
        return node;
    }

    private static AnnotationNode mixinOf(String internalName) {
        AnnotationNode mixin = new AnnotationNode(MIXIN);
        mixin.values = new ArrayList<>(List.of("value",
                new ArrayList<>(List.of(Type.getObjectType(internalName)))));
        return mixin;
    }

    private static AnnotationNode at(String value, String target) {
        AnnotationNode at = new AnnotationNode(AT);
        at.values = new ArrayList<>(List.of("value", value));
        if (target != null) {
            at.values.add("target");
            at.values.add(target);
        }
        return at;
    }

    private static MethodNode injector(String name, String selector, AnnotationNode at) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, name, "(" + CALLBACK + ")V", null, null);
        AnnotationNode inject = new AnnotationNode(INJECT);
        inject.values = new ArrayList<>(List.of(
                "method", new ArrayList<>(List.of(selector)),
                "at", at,
                "require", 1));
        method.visibleAnnotations = new ArrayList<>(List.of(inject));
        return method;
    }

    // ------------------------------------------------------------- the build

    /**
     * The list javac used. Passed in by the build rather than rebuilt here,
     * because a checker that assembles its own idea of the classpath is checking
     * something other than what shipped.
     */
    private static ClassLookup.OnPath compileClasspath() {
        String path = System.getProperty("kapok.audit.targets");
        assertNotNull(path, "kapok.audit.targets is not set; the test task has to pass the compile classpath");
        ClassLookup.OnPath lookup = ClassLookup.OnPath.of(path);
        // The game half of that list is one artifact moddev produces, and it is
        // the patched neoforge-<version>.jar rather than the merged jar it is
        // built from — the merged one is what javap reads, not what javac does.
        boolean game = lookup.entries().stream().anyMatch(p -> p.getFileName().toString().startsWith("neoforge-")
                && p.getParent() != null && p.getParent().endsWith(Path.of("moddev", "artifacts")));
        assertTrue(game, () -> "no NeoForge artifact on the classpath the audit was given: " + path);
        return lookup;
    }

    /** The compiled mixins, read off the test classpath, which already carries the main output. */
    private static ClassLookup mixinClasses() {
        return internalName -> {
            try (InputStream in = MixinTargetAuditTest.class.getClassLoader()
                    .getResourceAsStream(internalName + ".class")) {
                if (in == null) {
                    return null;
                }
                ClassNode node = new ClassNode();
                new org.objectweb.asm.ClassReader(in.readAllBytes())
                        .accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
                return new ClassLookup.Found(node, "build output");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /**
     * Reads the configs the way the loader does: the list in
     * {@code neoforge.mods.toml}, not whatever happens to sit in the resources
     * directory. A config file nobody registered would otherwise be audited
     * carefully and never loaded.
     */
    private static List<MixinAudit.ConfigSpec> shippedConfigs() throws IOException, URISyntaxException {
        String toml = text("META-INF/neoforge.mods.toml");
        Set<String> registered = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*config\\s*=\\s*\"([^\"]+)\"").matcher(toml);
        while (matcher.find()) {
            registered.add(matcher.group(1));
        }
        assertFalse(registered.isEmpty(), "neoforge.mods.toml registers no mixin config");
        assertEquals(present(), registered,
                "every mixin config in the resources has to be registered in neoforge.mods.toml, and the other way round");

        List<MixinAudit.ConfigSpec> out = new ArrayList<>();
        for (String name : registered) {
            Map<String, Object> json = Json.parseObject(text(name));
            List<String> entries = new ArrayList<>();
            for (String key : List.of("mixins", "client", "server")) {
                if (json.get(key) instanceof List<?> values) {
                    values.forEach(v -> entries.add((String) v));
                }
            }
            int defaultRequire = 0;
            if (json.get("injectors") instanceof Map<?, ?> injectors
                    && injectors.get("defaultRequire") instanceof Integer value) {
                defaultRequire = value;
            }
            out.add(new MixinAudit.ConfigSpec(name, (String) json.get("package"), entries, defaultRequire));
        }
        return out;
    }

    /** @return the mixin config files that are actually in the built resources. */
    private static Set<String> present() throws IOException, URISyntaxException {
        URL any = MixinTargetAuditTest.class.getResource("/kapok-chunk.mixins.json");
        assertNotNull(any, "the built resources are not on the test classpath");
        Path directory = Path.of(any.toURI()).getParent();
        Set<String> out = new LinkedHashSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.mixins.json")) {
            files.forEach(file -> out.add(file.getFileName().toString()));
        }
        return out;
    }

    private static String text(String resource) throws IOException {
        try (InputStream in = MixinTargetAuditTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, () -> resource + " is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // -------------------------------------------------------------- reporting

    private static Map<String, Integer> countByCategory(List<MixinAudit.Finding> findings) {
        Map<String, Integer> out = new LinkedHashMap<>();
        findings.forEach(f -> out.merge(f.category(), 1, Integer::sum));
        return out;
    }

    private static String report(List<MixinAudit.Finding> findings, List<MixinAudit.ConfigSpec> configs) {
        StringBuilder sb = new StringBuilder();
        sb.append("mixin target audit — ").append(configs.size()).append(" config(s), ")
                .append(configs.stream().mapToInt(c -> c.mixins().size()).sum()).append(" mixin class(es)\n");
        findings.forEach(f -> sb.append(f).append('\n'));
        long bad = findings.stream().filter(f -> !f.ok()).count();
        sb.append("--- ").append(findings.size()).append(" item(s), ").append(findings.size() - bad)
                .append(" OK, ").append(bad).append(" NG\n");
        new TreeMap<>(countByCategory(findings))
                .forEach((category, count) -> sb.append("    ").append(category).append(": ").append(count).append('\n'));
        return sb.toString();
    }

    private static void writeReport(String report) throws IOException {
        String path = System.getProperty("kapok.audit.report");
        if (path == null) {
            return;
        }
        Path file = Path.of(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, report, StandardCharsets.UTF_8);
    }
}
