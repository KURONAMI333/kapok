package com.kuronami.kapokcore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * Compares two {@link ModFileScanData} the way their consumers read them.
 *
 * <p>Not {@code Set.equals}, because that would report a difference on data the loader
 * treats as the same. ASM hands whole primitive arrays to
 * {@code AnnotationVisitor.visit} when an annotation holds an array of primitives, and
 * arrays have identity equality: a restored annotation carrying an {@code int[]} is never
 * {@code equals} to the freshly scanned one, however identical its contents, and is not
 * {@code equals} to itself across a round trip either. Comparing on equality would drown
 * a real disagreement in one line per such annotation.
 *
 * <p>Every value is therefore reduced to a description that spells out its type and its
 * contents, and the descriptions are compared. That also makes the report readable: what
 * comes out names the class or annotation that differs rather than an index.
 *
 * <p>Both directions are reported, and so is order. The sets are {@code LinkedHashSet}
 * filled in walk order, and a consumer that takes the first match of something would
 * notice a reshuffle that a plain membership check would not.
 */
final class ScanDataComparison {
    /** Enough to show the shape of a disagreement without filling the log with one jar. */
    private static final int REPORTED = 5;

    private ScanDataComparison() {
    }

    /** @return one line per disagreement, empty when the two describe the same scan */
    static List<String> differences(ModFileScanData expected, ModFileScanData actual) {
        List<String> differences = new ArrayList<>();
        compare(differences, "class", describeAll(expected.getClasses()), describeAll(actual.getClasses()));
        compare(differences, "annotation",
                describeAll(expected.getAnnotations()), describeAll(actual.getAnnotations()));
        return differences;
    }

    /** Whether two scan data elements would be read the same way by every consumer. */
    static boolean same(Object left, Object right) {
        return describe(left).equals(describe(right));
    }

    private static List<String> describeAll(Iterable<?> elements) {
        List<String> described = new ArrayList<>();
        for (Object element : elements) {
            described.add(describe(element));
        }
        return described;
    }

    private static void compare(List<String> differences, String what,
            List<String> expected, List<String> actual) {
        List<String> section = new ArrayList<>();
        if (expected.size() != actual.size()) {
            section.add(what + " count: scanned " + expected.size()
                    + ", restored " + actual.size());
        }
        report(section, "only in the fresh scan", what, missing(expected, actual));
        report(section, "only in the stored scan", what, missing(actual, expected));

        // Only worth saying when both sides hold the same things: otherwise the first
        // position at which they differ is wherever the missing one used to be, which
        // says nothing the lines above have not already said.
        if (section.isEmpty()) {
            for (int i = 0; i < expected.size(); i++) {
                if (!expected.get(i).equals(actual.get(i))) {
                    section.add(what + " order differs from position " + i + ": scanned "
                            + expected.get(i) + ", restored " + actual.get(i));
                    break;
                }
            }
        }
        differences.addAll(section);
    }

    private static void report(List<String> differences, String side, String what, List<String> found) {
        for (int i = 0; i < found.size() && i < REPORTED; i++) {
            differences.add(what + " " + side + ": " + found.get(i));
        }
        if (found.size() > REPORTED) {
            differences.add(what + " " + side + ": and " + (found.size() - REPORTED) + " more");
        }
    }

    /**
     * Multiset difference. A count rather than a set, so a duplicate that only one side
     * has is still reported.
     */
    private static List<String> missing(List<String> from, List<String> in) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String each : in) {
            counts.merge(each, 1, Integer::sum);
        }
        List<String> only = new ArrayList<>();
        for (String each : from) {
            Integer remaining = counts.get(each);
            if (remaining == null || remaining == 0) {
                only.add(each);
            } else {
                counts.put(each, remaining - 1);
            }
        }
        return only;
    }

    /**
     * A value written out far enough that two descriptions are equal exactly when the two
     * values are interchangeable. Types are named because {@code "1"} and {@code 1} are
     * different values with the same text, and arrays are written out because their own
     * {@code toString} says only where they live.
     */
    static String describe(Object value) {
        StringBuilder text = new StringBuilder();
        describe(value, text);
        return text.toString();
    }

    private static void describe(Object value, StringBuilder text) {
        switch (value) {
            case null -> text.append("null");
            case ModFileScanData.ClassData clazz -> {
                text.append("class ").append(clazz.clazz()).append(" extends ").append(clazz.parent());
                text.append(" implements ");
                describeSorted(clazz.interfaces(), text);
            }
            case ModFileScanData.AnnotationData annotation -> {
                text.append("@").append(annotation.annotationType())
                        .append(" on ").append(annotation.targetType())
                        .append(' ').append(annotation.clazz())
                        .append('#').append(annotation.memberName())
                        .append(' ');
                describe(annotation.annotationData(), text);
            }
            case Map<?, ?> map -> {
                // Sorted, because the loader's own maps are HashMaps whose iteration order
                // is not part of what a consumer sees.
                text.append("map{");
                Map<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    sorted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                boolean first = true;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) {
                        text.append(", ");
                    }
                    first = false;
                    text.append(entry.getKey()).append('=');
                    describe(entry.getValue(), text);
                }
                text.append('}');
            }
            case List<?> list -> {
                text.append("list[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        text.append(", ");
                    }
                    describe(list.get(i), text);
                }
                text.append(']');
            }
            case byte[] array -> text.append("byte").append(Arrays.toString(array));
            case boolean[] array -> text.append("boolean").append(Arrays.toString(array));
            case char[] array -> text.append("char").append(Arrays.toString(array));
            case short[] array -> text.append("short").append(Arrays.toString(array));
            case int[] array -> text.append("int").append(Arrays.toString(array));
            case long[] array -> text.append("long").append(Arrays.toString(array));
            case float[] array -> text.append("float").append(Arrays.toString(array));
            case double[] array -> text.append("double").append(Arrays.toString(array));
            case Object[] array -> {
                text.append("array[");
                for (int i = 0; i < array.length; i++) {
                    if (i > 0) {
                        text.append(", ");
                    }
                    describe(array[i], text);
                }
                text.append(']');
            }
            default -> text.append(value.getClass().getSimpleName()).append('(').append(value).append(')');
        }
    }

    private static void describeSorted(Iterable<?> values, StringBuilder text) {
        List<String> described = new ArrayList<>();
        for (Object each : values) {
            described.add(describe(each));
        }
        described.sort(null);
        text.append(described);
    }
}
