package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class ScanDataCodecTest {
    @Test
    void restoresEveryClassInOrder() throws IOException {
        ModFileScanData original = populated();

        ModFileScanData restored = roundTrip(original);

        List<ModFileScanData.ClassData> before = List.copyOf(original.getClasses());
        List<ModFileScanData.ClassData> after = List.copyOf(restored.getClasses());
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i), after.get(i), "class " + i);
        }
    }

    @Test
    void restoresEveryAnnotationInOrder() throws IOException {
        ModFileScanData original = populated();

        ModFileScanData restored = roundTrip(original);

        assertEquals(List.of(), ScanDataComparison.differences(original, restored));
    }

    @Test
    void keepsTypesInterchangeableWithTheOnesAsmProduced() throws IOException {
        ModFileScanData original = populated();

        ModFileScanData restored = roundTrip(original);

        // getObjectType and getType build a Type with different internal sorts. Only
        // equals and hashCode decide whether a consumer can tell, and both normalise, so
        // an object type written as a descriptor comes back indistinguishable.
        ModFileScanData.ClassData first = List.copyOf(restored.getClasses()).getFirst();
        assertEquals(Type.getObjectType("com/example/Alpha"), first.clazz());
        assertEquals(Type.getObjectType("com/example/Alpha").hashCode(), first.clazz().hashCode());
        assertEquals(Set.of(Type.getObjectType("com/example/Marker")), first.interfaces());
    }

    @Test
    void keepsAClassWithNoSuperName() throws IOException {
        ModFileScanData restored = roundTrip(populated());

        ModFileScanData.ClassData orphan = List.copyOf(restored.getClasses()).stream()
                .filter(each -> each.clazz().equals(Type.getObjectType("java/lang/Object")))
                .findFirst()
                .orElseThrow();
        assertNull(orphan.parent(), "superName is null for java/lang/Object");
        assertEquals(Set.of(), orphan.interfaces());
    }

    @Test
    void answersGetAnnotatedByTheSameWayAfterwards() throws IOException {
        ModFileScanData original = populated();

        ModFileScanData restored = roundTrip(original);

        List<ModFileScanData.AnnotationData> before =
                original.getAnnotatedBy(Retention.class, ElementType.TYPE).toList();
        List<ModFileScanData.AnnotationData> after =
                restored.getAnnotatedBy(Retention.class, ElementType.TYPE).toList();
        assertEquals(1, before.size(), "the fixture has to contain something to find");
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertTrue(ScanDataComparison.same(before.get(i), after.get(i)));
        }
    }

    @Test
    void restoresEveryValueTypeAnAnnotationCanHold() throws IOException {
        ModFileScanData restored = roundTrip(populated());

        Map<String, Object> values = List.copyOf(restored.getAnnotations()).getFirst().annotationData();
        assertEquals((byte) 7, values.get("byte"));
        assertEquals(true, values.get("boolean"));
        assertEquals('x', values.get("char"));
        assertEquals((short) 9, values.get("short"));
        assertEquals(11, values.get("int"));
        assertEquals(12L, values.get("long"));
        assertEquals(1.5f, values.get("float"));
        assertEquals(2.5d, values.get("double"));
        assertEquals("text", values.get("string"));
        assertEquals(Type.getObjectType("com/example/Alpha"), values.get("classLiteral"));
        assertEquals(Type.INT_TYPE, values.get("primitiveLiteral"));
        assertEquals(Type.getType("[Ljava/lang/String;"), values.get("arrayLiteral"));
        assertEquals(Type.VOID_TYPE, values.get("voidLiteral"));
        assertEquals(new ModAnnotation.EnumHolder("Lcom/example/Colour;", "RED"), values.get("enum"));
        assertNull(values.get("absent"));
        assertTrue(values.containsKey("absent"), "a null value is not the same as no value");

        assertInstanceOf(ArrayList.class, values.get("list"));
        assertEquals(List.of("a", "b"), values.get("list"));

        Map<?, ?> child = assertInstanceOf(Map.class, values.get("child"));
        assertEquals(3, child.get("depth"));
        assertTrue(Arrays.equals(new int[] {4, 5}, (int[]) child.get("nestedInts")));

        assertTrue(Arrays.equals(new byte[] {1, 2}, (byte[]) values.get("bytes")));
        assertTrue(Arrays.equals(new boolean[] {true, false}, (boolean[]) values.get("booleans")));
        assertTrue(Arrays.equals(new char[] {'a', 'b'}, (char[]) values.get("chars")));
        assertTrue(Arrays.equals(new short[] {3, 4}, (short[]) values.get("shorts")));
        assertTrue(Arrays.equals(new int[] {5, 6}, (int[]) values.get("ints")));
        assertTrue(Arrays.equals(new long[] {7L, 8L}, (long[]) values.get("longs")));
        assertTrue(Arrays.equals(new float[] {9f}, (float[]) values.get("floats")));
        assertTrue(Arrays.equals(new double[] {10d}, (double[]) values.get("doubles")));
    }

    @Test
    void keepsAnAnnotationWithNoMember() throws IOException {
        ModFileScanData restored = roundTrip(populated());

        ModFileScanData.AnnotationData last = List.copyOf(restored.getAnnotations()).getLast();
        assertNull(last.memberName());
        assertNull(last.annotationData());
    }

    @Test
    void sharesOneCopyOfAStringAcrossRestoredScans() throws IOException {
        byte[] stored = ScanDataCodec.encode(populated());

        ModFileScanData first = ScanDataCodec.decode(stored, () -> null);
        ModFileScanData second = ScanDataCodec.decode(stored, () -> null);

        // Every mod's scan repeats the same member names and string values, and all of
        // them stay alive for the whole game, so two restores must not hold two copies.
        String firstName = List.copyOf(first.getAnnotations()).get(1).memberName();
        String secondName = List.copyOf(second.getAnnotations()).get(1).memberName();
        assertEquals("run", firstName);
        assertSame(firstName, secondName);
        assertSame(
                List.copyOf(first.getAnnotations()).getFirst().annotationData().get("string"),
                List.copyOf(second.getAnnotations()).getFirst().annotationData().get("string"));
    }

    @Test
    void refusesAValueTheLoaderCannotProduce() {
        ModFileScanData data = new ModFileScanData();
        data.getAnnotations().add(new ModFileScanData.AnnotationData(
                Type.getType(Retention.class), ElementType.TYPE,
                Type.getObjectType("com/example/Alpha"), "member",
                Map.of("what", new StringBuilder("not a value ASM hands out"))));

        // Refusing is the point. A value we do not recognise is a value we would restore
        // wrongly, and the caller answers a refusal by scanning for real.
        IOException refused = assertThrows(IOException.class, () -> ScanDataCodec.encode(data));
        assertTrue(refused.getMessage().contains("StringBuilder"), refused.getMessage());
    }

    @Test
    void refusesSomethingThatIsNotOneOfItsFiles() {
        assertThrows(IOException.class,
                () -> ScanDataCodec.decode(new byte[] {1, 2, 3, 4}, () -> null));
    }

    @Test
    void refusesATruncatedFile() throws IOException {
        byte[] whole = ScanDataCodec.encode(populated());
        byte[] half = Arrays.copyOf(whole, whole.length / 2);

        assertThrows(IOException.class, () -> ScanDataCodec.decode(half, () -> null));
    }

    @Test
    void putsTheLiveModFileInfoBackAndStoresNone() throws IOException {
        ModFileScanData restored = roundTrip(populated());

        // Scanner.scan does exactly this before it walks, and consumers read it back out
        // of getIModInfoData.
        assertEquals(1, restored.getIModInfoData().size());
        assertFalse(ScanDataCodec.encode(restored).length == 0);
    }

    private static ModFileScanData roundTrip(ModFileScanData original) throws IOException {
        return ScanDataCodec.decode(ScanDataCodec.encode(original), () -> null);
    }

    /** One of everything the four {@code ModAnnotationVisitor} methods can produce. */
    private static ModFileScanData populated() {
        ModFileScanData data = new ModFileScanData();
        data.getClasses().add(new ModFileScanData.ClassData(
                Type.getObjectType("com/example/Alpha"),
                Type.getObjectType("com/example/Base"),
                new LinkedHashSet<>(List.of(Type.getObjectType("com/example/Marker")))));
        data.getClasses().add(new ModFileScanData.ClassData(
                Type.getObjectType("java/lang/Object"), null, Set.of()));
        data.getClasses().add(new ModFileScanData.ClassData(
                Type.getObjectType("com/example/Beta"),
                Type.getObjectType("com/example/Alpha"),
                new LinkedHashSet<>(List.of(
                        Type.getObjectType("com/example/Marker"),
                        Type.getObjectType("com/example/Second")))));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("depth", 3);
        nested.put("nestedInts", new int[] {4, 5});

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("byte", (byte) 7);
        values.put("boolean", true);
        values.put("char", 'x');
        values.put("short", (short) 9);
        values.put("int", 11);
        values.put("long", 12L);
        values.put("float", 1.5f);
        values.put("double", 2.5d);
        values.put("string", "text");
        values.put("classLiteral", Type.getObjectType("com/example/Alpha"));
        values.put("primitiveLiteral", Type.INT_TYPE);
        values.put("arrayLiteral", Type.getType("[Ljava/lang/String;"));
        values.put("voidLiteral", Type.VOID_TYPE);
        values.put("enum", new ModAnnotation.EnumHolder("Lcom/example/Colour;", "RED"));
        values.put("list", new ArrayList<>(List.of("a", "b")));
        values.put("child", nested);
        values.put("absent", null);
        values.put("bytes", new byte[] {1, 2});
        values.put("booleans", new boolean[] {true, false});
        values.put("chars", new char[] {'a', 'b'});
        values.put("shorts", new short[] {3, 4});
        values.put("ints", new int[] {5, 6});
        values.put("longs", new long[] {7L, 8L});
        values.put("floats", new float[] {9f});
        values.put("doubles", new double[] {10d});

        data.getAnnotations().add(new ModFileScanData.AnnotationData(
                Type.getType(Retention.class), ElementType.TYPE,
                Type.getObjectType("com/example/Alpha"), "com.example.Alpha", values));
        data.getAnnotations().add(new ModFileScanData.AnnotationData(
                Type.getObjectType("com/example/Mod"), ElementType.METHOD,
                Type.getObjectType("com/example/Beta"), "run", Map.of()));
        data.getAnnotations().add(new ModFileScanData.AnnotationData(
                Type.getObjectType("com/example/Mod"), ElementType.FIELD,
                Type.getObjectType("com/example/Beta"), null, null));
        return data;
    }
}
