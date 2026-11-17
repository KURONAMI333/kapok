package com.kuronami.kapokcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class ScanDataComparisonTest {
    @Test
    void treatsPrimitiveArraysWithTheSameContentsAsTheSameValue() {
        ModFileScanData.AnnotationData one = withValue(new int[] {1, 2, 3});
        ModFileScanData.AnnotationData other = withValue(new int[] {1, 2, 3});

        // The reason this class exists. Arrays have identity equality, so the loader's own
        // record says these are different; every consumer that reads the value says they
        // are not.
        assertFalse(one.equals(other));
        assertTrue(ScanDataComparison.same(one, other));
    }

    @Test
    void seesThroughDifferentArrayContents() {
        assertFalse(ScanDataComparison.same(withValue(new int[] {1, 2}), withValue(new int[] {1, 3})));
        assertFalse(ScanDataComparison.same(withValue(new int[] {1}), withValue(new long[] {1})));
    }

    @Test
    void doesNotConfuseAStringWithTheNumberItSpells() {
        assertFalse(ScanDataComparison.same(withValue("1"), withValue(1)));
    }

    @Test
    void namesWhatOnlyOneSideHas() {
        ModFileScanData scanned = data(alpha(), beta());
        ModFileScanData restored = data(alpha());

        List<String> differences = ScanDataComparison.differences(scanned, restored);

        assertTrue(differences.stream().anyMatch(line -> line.contains("count: scanned 2, restored 1")),
                differences.toString());
        assertTrue(differences.stream().anyMatch(line ->
                line.contains("only in the fresh scan") && line.contains("com/example/Beta")),
                differences.toString());
    }

    @Test
    void noticesAReshuffleEvenWhenBothSidesHoldTheSameThings() {
        ModFileScanData scanned = data(alpha(), beta());
        ModFileScanData restored = data(beta(), alpha());

        List<String> differences = ScanDataComparison.differences(scanned, restored);

        assertTrue(differences.stream().anyMatch(line -> line.contains("order differs from position 0")),
                differences.toString());
    }

    @Test
    void saysNothingAboutTwoIdenticalScans() {
        assertEquals(List.of(), ScanDataComparison.differences(data(alpha(), beta()), data(alpha(), beta())));
    }

    private static ModFileScanData data(ModFileScanData.ClassData... classes) {
        ModFileScanData data = new ModFileScanData();
        for (ModFileScanData.ClassData each : classes) {
            data.getClasses().add(each);
        }
        return data;
    }

    private static ModFileScanData.ClassData alpha() {
        return new ModFileScanData.ClassData(Type.getObjectType("com/example/Alpha"),
                Type.getObjectType("java/lang/Object"), Set.of());
    }

    private static ModFileScanData.ClassData beta() {
        return new ModFileScanData.ClassData(Type.getObjectType("com/example/Beta"),
                Type.getObjectType("java/lang/Object"), Set.of());
    }

    private static ModFileScanData.AnnotationData withValue(Object value) {
        return new ModFileScanData.AnnotationData(
                Type.getObjectType("com/example/Mod"), ElementType.TYPE,
                Type.getObjectType("com/example/Alpha"), "com.example.Alpha",
                Map.of("value", value));
    }
}
