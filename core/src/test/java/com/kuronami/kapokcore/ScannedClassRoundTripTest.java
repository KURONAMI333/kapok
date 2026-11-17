package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import net.neoforged.fml.loading.modscan.ModClassVisitor;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

/**
 * Round trips scan data that the loader's own visitor produced from real class bytes.
 *
 * <p>The hand-built fixture in {@link ScanDataCodecTest} says the codec handles the value
 * types we believe ASM emits. This says it handles the ones it actually emits, which is a
 * different claim: whether a member becomes a primitive array, an {@code ArrayList}, an
 * {@code EnumHolder} or a nested map is ASM's decision, not ours, and reading it wrong
 * would produce a cache that is quietly not the thing it replaced.
 */
class ScannedClassRoundTripTest {
    @Test
    void restoresEverythingTheLoadersVisitorProduced() throws IOException {
        ModFileScanData scanned = scan(RichlyAnnotated.class, RichlyAnnotated.Colour.class,
                RichlyAnnotated.Inner.class, RichlyAnnotated.Everything.class);

        assertNotEquals(0, scanned.getClasses().size(), "the scan has to have found something");
        assertNotEquals(0, scanned.getAnnotations().size());

        ModFileScanData restored =
                ScanDataCodec.decode(ScanDataCodec.encode(scanned), () -> null);

        assertEquals(List.of(), ScanDataComparison.differences(scanned, restored));
    }

    @Test
    void theFixtureReallyExercisesEveryValueShape() throws IOException {
        Map<String, Object> values = everything(scan(RichlyAnnotated.class));

        // Written as assertions rather than trusted, because these are ASM's choices. If a
        // future ASM stops handing over primitive arrays whole, this is where it shows up
        // - and the codec branch that would then be untested is named here.
        assertInstanceOf(String.class, values.get("text"));
        assertInstanceOf(Integer.class, values.get("number"));
        assertInstanceOf(int[].class, values.get("numbers"));
        assertInstanceOf(long[].class, values.get("longs"));
        assertInstanceOf(double[].class, values.get("doubles"));
        assertInstanceOf(boolean[].class, values.get("flags"));
        assertInstanceOf(char[].class, values.get("letters"));
        assertInstanceOf(List.class, values.get("words"));
        assertInstanceOf(Type.class, values.get("literal"));
        assertEquals(Type.INT_TYPE, values.get("primitiveLiteral"));
        assertEquals(Type.getType(String[].class), values.get("arrayLiteral"));
        assertInstanceOf(ModAnnotation.EnumHolder.class, values.get("colour"));
        assertInstanceOf(List.class, values.get("colours"));
        assertInstanceOf(ModAnnotation.EnumHolder.class, ((List<?>) values.get("colours")).getFirst());
        assertInstanceOf(Map.class, values.get("nested"));
        assertInstanceOf(List.class, values.get("several"));
        assertInstanceOf(Map.class, ((List<?>) values.get("several")).getFirst());
    }

    @Test
    void restoresANestedAnnotationsOwnValues() throws IOException {
        ModFileScanData restored = ScanDataCodec.decode(
                ScanDataCodec.encode(scan(RichlyAnnotated.class)), () -> null);

        Map<?, ?> nested = (Map<?, ?>) everything(restored).get("nested");
        assertEquals(2, nested.get("depth"));
        assertEquals(List.of("x"), nested.get("tags"));
    }

    @Test
    void findsTheSameAnnotatedMembersAfterwards() throws IOException {
        ModFileScanData scanned = scan(RichlyAnnotated.class);
        ModFileScanData restored = ScanDataCodec.decode(ScanDataCodec.encode(scanned), () -> null);

        for (ElementType where : List.of(ElementType.TYPE, ElementType.FIELD, ElementType.METHOD)) {
            List<String> before = named(scanned, where);
            List<String> after = named(restored, where);
            assertEquals(before, after, where.toString());
            assertTrue(before.size() >= 1, "the fixture is annotated on " + where);
        }
    }

    private static List<String> named(ModFileScanData data, ElementType where) {
        Type everything = Type.getType(RichlyAnnotated.Everything.class);
        return data.getAnnotations().stream()
                .filter(each -> each.annotationType().equals(everything) && each.targetType() == where)
                .map(each -> each.clazz() + "#" + each.memberName())
                .sorted()
                .toList();
    }

    private static Map<String, Object> everything(ModFileScanData data) {
        Type everything = Type.getType(RichlyAnnotated.Everything.class);
        return data.getAnnotations().stream()
                .filter(each -> each.annotationType().equals(everything)
                        && each.targetType() == ElementType.TYPE)
                .map(ModFileScanData.AnnotationData::annotationData)
                .findFirst()
                .orElseThrow();
    }

    /** What {@code Scanner.fileVisitor} does, over classes that are already on disk. */
    private static ModFileScanData scan(Class<?>... classes) throws IOException {
        ModFileScanData data = new ModFileScanData();
        data.addModFileInfo(null);
        for (Class<?> each : classes) {
            String resource = "/" + each.getName().replace('.', '/') + ".class";
            try (InputStream bytes = ScannedClassRoundTripTest.class.getResourceAsStream(resource)) {
                if (bytes == null) {
                    throw new IOException("no compiled form of " + each.getName());
                }
                ModClassVisitor visitor = new ModClassVisitor();
                new ClassReader(bytes).accept(visitor,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                visitor.buildData(data.getClasses(), data.getAnnotations());
            }
        }
        return data;
    }
}
