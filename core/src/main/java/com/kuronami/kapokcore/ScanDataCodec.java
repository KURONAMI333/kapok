package com.kuronami.kapokcore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.neoforged.fml.loading.modscan.ModAnnotation;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import org.objectweb.asm.Type;

/**
 * Writes a {@link ModFileScanData} to bytes and reads it back.
 *
 * <p>Only the two sets are stored. The third field, {@code modFiles}, holds live
 * {@code IModFileInfo} objects belonging to the current launch; the restoring side has
 * the real one to hand and puts it back, exactly as {@code Scanner.scan} does.
 *
 * <p>The value space is closed, and it is closed by the loader rather than by us: the
 * only things that ever enter an annotation's value map come from ASM calling the four
 * methods of {@code ModAnnotationVisitor}. That admits boxed primitives, {@code String},
 * {@code Type}, {@code EnumHolder}, an {@code ArrayList} of those, a child annotation's
 * own map, and the primitive arrays ASM passes whole. Anything else is a value the loader
 * cannot produce, so meeting one means our reading of it is wrong - {@link #encode}
 * refuses rather than guessing, and the caller falls back to a real scan.
 *
 * <p>{@code Type} is written as its descriptor because {@code Type.equals} and
 * {@code Type.hashCode} are defined on the descriptor's characters, so
 * {@code Type.getType(t.getDescriptor())} is indistinguishable from {@code t} to every
 * consumer, including the ones {@code getObjectType} produced with a different internal
 * sort.
 */
final class ScanDataCodec {
    /** Recognisable in a hex dump, and cheap protection against reading a foreign file. */
    private static final int MAGIC = 0xA6B0_0B07;

    /**
     * Bumped by hand whenever anything below changes shape or meaning. It is part of the
     * cache key too, so an older jar's files are ignored rather than mis-read.
     */
    static final int FORMAT = 1;

    private static final byte NULL = 0;
    private static final byte BYTE = 1;
    private static final byte BOOLEAN = 2;
    private static final byte CHAR = 3;
    private static final byte SHORT = 4;
    private static final byte INT = 5;
    private static final byte LONG = 6;
    private static final byte FLOAT = 7;
    private static final byte DOUBLE = 8;
    private static final byte STRING = 9;
    private static final byte TYPE = 10;
    private static final byte ENUM = 11;
    private static final byte LIST = 12;
    private static final byte CHILD = 13;
    private static final byte BYTE_ARRAY = 14;
    private static final byte BOOLEAN_ARRAY = 15;
    private static final byte CHAR_ARRAY = 16;
    private static final byte SHORT_ARRAY = 17;
    private static final byte INT_ARRAY = 18;
    private static final byte LONG_ARRAY = 19;
    private static final byte FLOAT_ARRAY = 20;
    private static final byte DOUBLE_ARRAY = 21;

    private ScanDataCodec() {
    }

    /**
     * @throws IOException when the data contains a value this format does not describe.
     *                     Refusing is the safe direction: the caller keeps the real scan
     *                     and simply writes nothing.
     */
    static byte[] encode(ModFileScanData data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);

            Set<ModFileScanData.ClassData> classes = data.getClasses();
            out.writeInt(classes.size());
            for (ModFileScanData.ClassData clazz : classes) {
                writeType(out, clazz.clazz());
                writeType(out, clazz.parent());
                Set<Type> interfaces = clazz.interfaces();
                out.writeInt(interfaces.size());
                for (Type each : interfaces) {
                    writeType(out, each);
                }
            }

            Set<ModFileScanData.AnnotationData> annotations = data.getAnnotations();
            out.writeInt(annotations.size());
            for (ModFileScanData.AnnotationData annotation : annotations) {
                writeType(out, annotation.annotationType());
                writeString(out, annotation.targetType() == null ? null : annotation.targetType().name());
                writeType(out, annotation.clazz());
                writeString(out, annotation.memberName());
                writeMap(out, annotation.annotationData());
            }
        }
        return bytes.toByteArray();
    }

    /**
     * @param modFileInfo the live info for the file being restored, put back where
     *                    {@code Scanner.scan} would have put it. Called at most once.
     */
    static ModFileScanData decode(byte[] stored, Supplier<IModFileInfo> modFileInfo) throws IOException {
        ModFileScanData data = new ModFileScanData();
        data.addModFileInfo(modFileInfo.get());

        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(stored)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("not a kapok core scan cache file");
            }
            int format = in.readInt();
            if (format != FORMAT) {
                throw new IOException("scan cache format " + format + ", expected " + FORMAT);
            }

            Set<ModFileScanData.ClassData> classes = data.getClasses();
            int classCount = readCount(in);
            for (int i = 0; i < classCount; i++) {
                Type clazz = readType(in);
                Type parent = readType(in);
                int interfaceCount = readCount(in);
                Set<Type> interfaces = new LinkedHashSet<>();
                for (int j = 0; j < interfaceCount; j++) {
                    interfaces.add(readType(in));
                }
                classes.add(new ModFileScanData.ClassData(clazz, parent, interfaces));
            }

            Set<ModFileScanData.AnnotationData> annotations = data.getAnnotations();
            int annotationCount = readCount(in);
            for (int i = 0; i < annotationCount; i++) {
                Type annotationType = readType(in);
                String target = readString(in);
                Type clazz = readType(in);
                String member = readString(in);
                Map<String, Object> values = readMap(in);
                annotations.add(new ModFileScanData.AnnotationData(
                        annotationType,
                        target == null ? null : ElementType.valueOf(target),
                        clazz,
                        member,
                        values));
            }
        } catch (RuntimeException malformed) {
            // ElementType.valueOf, and anything else that decides a value is impossible.
            // Presented as an IOException so the one caller has one failure branch.
            throw new IOException("corrupt scan cache entry", malformed);
        }
        return data;
    }

    private static void writeMap(DataOutputStream out, Map<String, Object> values) throws IOException {
        if (values == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(values.size());
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            writeString(out, entry.getKey());
            writeValue(out, entry.getValue());
        }
    }

    private static Map<String, Object> readMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            values.put(readString(in), readValue(in));
        }
        return values;
    }

    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        switch (value) {
            case null -> out.writeByte(NULL);
            case Byte v -> {
                out.writeByte(BYTE);
                out.writeByte(v);
            }
            case Boolean v -> {
                out.writeByte(BOOLEAN);
                out.writeBoolean(v);
            }
            case Character v -> {
                out.writeByte(CHAR);
                out.writeChar(v);
            }
            case Short v -> {
                out.writeByte(SHORT);
                out.writeShort(v);
            }
            case Integer v -> {
                out.writeByte(INT);
                out.writeInt(v);
            }
            case Long v -> {
                out.writeByte(LONG);
                out.writeLong(v);
            }
            case Float v -> {
                out.writeByte(FLOAT);
                out.writeFloat(v);
            }
            case Double v -> {
                out.writeByte(DOUBLE);
                out.writeDouble(v);
            }
            case String v -> {
                out.writeByte(STRING);
                writeString(out, v);
            }
            case Type v -> {
                out.writeByte(TYPE);
                writeString(out, v.getDescriptor());
            }
            case ModAnnotation.EnumHolder v -> {
                // From addEnumProperty. Two strings, no live handle to the enum class.
                out.writeByte(ENUM);
                writeString(out, v.desc());
                writeString(out, v.value());
            }
            case java.util.List<?> v -> {
                // ModAnnotation collects array elements into an ArrayList between
                // addArray and endArray.
                out.writeByte(LIST);
                out.writeInt(v.size());
                for (Object element : v) {
                    writeValue(out, element);
                }
            }
            case Map<?, ?> v -> {
                // A child annotation's own value map, from addChildAnnotation.
                out.writeByte(CHILD);
                out.writeInt(v.size());
                for (Map.Entry<?, ?> entry : v.entrySet()) {
                    writeString(out, String.valueOf(entry.getKey()));
                    writeValue(out, entry.getValue());
                }
            }
            case byte[] v -> {
                out.writeByte(BYTE_ARRAY);
                out.writeInt(v.length);
                out.write(v);
            }
            case boolean[] v -> {
                out.writeByte(BOOLEAN_ARRAY);
                out.writeInt(v.length);
                for (boolean element : v) {
                    out.writeBoolean(element);
                }
            }
            case char[] v -> {
                out.writeByte(CHAR_ARRAY);
                out.writeInt(v.length);
                for (char element : v) {
                    out.writeChar(element);
                }
            }
            case short[] v -> {
                out.writeByte(SHORT_ARRAY);
                out.writeInt(v.length);
                for (short element : v) {
                    out.writeShort(element);
                }
            }
            case int[] v -> {
                out.writeByte(INT_ARRAY);
                out.writeInt(v.length);
                for (int element : v) {
                    out.writeInt(element);
                }
            }
            case long[] v -> {
                out.writeByte(LONG_ARRAY);
                out.writeInt(v.length);
                for (long element : v) {
                    out.writeLong(element);
                }
            }
            case float[] v -> {
                out.writeByte(FLOAT_ARRAY);
                out.writeInt(v.length);
                for (float element : v) {
                    out.writeFloat(element);
                }
            }
            case double[] v -> {
                out.writeByte(DOUBLE_ARRAY);
                out.writeInt(v.length);
                for (double element : v) {
                    out.writeDouble(element);
                }
            }
            default -> throw new IOException(
                    "annotation value of unexpected type " + value.getClass().getName());
        }
    }

    private static Object readValue(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case NULL -> null;
            case BYTE -> in.readByte();
            case BOOLEAN -> in.readBoolean();
            case CHAR -> in.readChar();
            case SHORT -> in.readShort();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case STRING -> readString(in);
            case TYPE -> Type.getType(readString(in));
            case ENUM -> readEnumHolder(in);
            case LIST -> readList(in);
            case CHILD -> readChild(in);
            case BYTE_ARRAY -> in.readNBytes(readCount(in));
            case BOOLEAN_ARRAY -> readBooleans(in);
            case CHAR_ARRAY -> readChars(in);
            case SHORT_ARRAY -> readShorts(in);
            case INT_ARRAY -> readInts(in);
            case LONG_ARRAY -> readLongs(in);
            case FLOAT_ARRAY -> readFloats(in);
            case DOUBLE_ARRAY -> readDoubles(in);
            default -> throw new IOException("unknown value tag " + tag);
        };
    }

    private static ArrayList<Object> readList(DataInputStream in) throws IOException {
        // ArrayList, not List.of: the loader hands consumers a mutable ArrayList and at
        // least one of them could be relying on that.
        int size = readCount(in);
        ArrayList<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readValue(in));
        }
        return list;
    }

    private static Map<String, Object> readChild(DataInputStream in) throws IOException {
        int size = readCount(in);
        Map<String, Object> child = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            child.put(readString(in), readValue(in));
        }
        return child;
    }

    private static boolean[] readBooleans(DataInputStream in) throws IOException {
        boolean[] values = new boolean[readCount(in)];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readBoolean();
        }
        return values;
    }

    private static char[] readChars(DataInputStream in) throws IOException {
        char[] values = new char[readCount(in)];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readChar();
        }
        return values;
    }

    private static short[] readShorts(DataInputStream in) throws IOException {
        short[] values = new short[readCount(in)];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readShort();
        }
        return values;
    }

    private static int[] readInts(DataInputStream in) throws IOException {
        int[] values = new int[readCount(in)];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readInt();
        }
        return values;
    }

    private static long[] readLongs(DataInputStream in) throws IOException {
        long[] values = new long[readCount(in)];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readLong();
        }
        return values;
    }

    private static float[] readFloats(DataInputStream in) throws IOException {
        float[] values = new float[readCount(in)];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readFloat();
        }
        return values;
    }

    private static double[] readDoubles(DataInputStream in) throws IOException {
        double[] values = new double[readCount(in)];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readDouble();
        }
        return values;
    }

    private static void writeType(DataOutputStream out, Type type) throws IOException {
        writeString(out, type == null ? null : type.getDescriptor());
    }

    private static Type readType(DataInputStream in) throws IOException {
        String descriptor = readString(in);
        return descriptor == null ? null : Type.getType(descriptor);
    }

    /**
     * Length-prefixed UTF-8 rather than {@code writeUTF}, whose two-byte length caps a
     * string at 64 KB. An annotation can carry a string constant of any size.
     */
    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    /**
     * Reads one string and returns the JVM's shared copy of it.
     *
     * <p>A restored scan keeps living for the whole game ({@code FMLModContainer}
     * holds it), and most of its strings are the same few descriptors and annotation
     * names repeated across every mod: in an ATM10 heap dump, sharing them would
     * remove 53 MB of the 155 MB the 535 scans hold ({@code SCANDATA_DEDUP.md}).
     * {@link String#intern} gives that sharing without a pool of our own to keep or
     * release, and an {@code asm.Type} built from an interned descriptor shares its
     * buffer too.
     */
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8).intern();
    }

    private static ModAnnotation.EnumHolder readEnumHolder(DataInputStream in) throws IOException {
        return new ModAnnotation.EnumHolder(readString(in), readString(in));
    }

    /**
     * A negative or absurd count means the file is not what it claims to be. Caught here
     * so a corrupt file cannot make us allocate an array of its choosing.
     */
    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("negative count " + count + " in scan cache entry");
        }
        return count;
    }
}
