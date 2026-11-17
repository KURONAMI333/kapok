package com.kuronami.kapokcore.jij;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Names the copy of one nested jar by where it came from, without reading it.
 *
 * <p>Three fields, all from the enclosing jar's central directory or from the path of the
 * enclosing jar itself, so producing a key costs one seek and no inflation - which is the
 * whole point, since inflating the nested jar is the work being removed.
 *
 * <ul>
 *   <li><b>The enclosing jar's real path.</b> Update a mod and its file changes, but a
 *       file's name usually does not, so the path alone cannot notice. It is here for the
 *       opposite reason: to keep two mods that ship the same library at the same entry
 *       name from writing over each other's copy while the loader is holding both open.
 *       {@code toRealPath} decides it, so case and links cannot produce two spellings of
 *       one file - and so a nested jar of a nested jar keys off the copy this class
 *       already made, which means updating a mod invalidates its whole subtree at once.
 *   <li><b>The entry name.</b> A library moved between {@code META-INF/jarjar/} and
 *       {@code META-INF/jars/} - both are in use - is a different entry, not the same one.
 *   <li><b>Uncompressed size and CRC-32 of the entry.</b> These are what actually notice
 *       a changed jar. They cover every byte of it, not only its classes.
 * </ul>
 *
 * <p>The digest is truncated to 128 bits. The full 256 would put another 32 characters in
 * front of every nested jar's name on a Windows path that already carries the game
 * directory, and what is being defended against here is an accident between a pack's ~150
 * nested jars, not an adversary: two of them must agree on path, entry name, length and
 * checksum to collide.
 */
final class NestedJarKey {
    /** Separator between fields. NUL cannot appear in a zip entry name or a path. */
    private static final char FIELD = '\0';

    private static final int BYTES = 16;

    private NestedJarKey() {
    }

    /**
     * @param enclosingRealPath the enclosing jar, as {@code Path#toRealPath} spells it
     * @param entryName         the nested jar's entry name, with {@code /} separators
     * @param size              uncompressed size, from the central directory
     * @param crc               CRC-32, from the central directory
     * @return 32 hex characters, usable as a directory name on every filesystem
     */
    static String of(Path enclosingRealPath, String entryName, long size, long crc) {
        MessageDigest digest = sha256();
        feed(digest, enclosingRealPath.toString());
        feed(digest, entryName);
        feed(digest, Long.toString(size));
        feed(digest, Long.toString(crc));
        byte[] full = digest.digest();
        byte[] head = new byte[BYTES];
        System.arraycopy(full, 0, head, 0, BYTES);
        return HexFormat.of().formatHex(head);
    }

    /** True when {@code name} is one this class could have produced. */
    static boolean looksLikeKey(String name) {
        if (name.length() != BYTES * 2) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * The entry name as the central directory spells it.
     *
     * <p>Jar-in-Jar's metadata carries the path as a string and the selector turns it into
     * a {@code Path} on the default filesystem, so on Windows what arrives here is
     * {@code META-INF\jarjar\foo.jar}. A zip entry name always uses {@code /}.
     */
    static String entryName(Path fromMetadata) {
        return fromMetadata.toString().replace('\\', '/');
    }

    private static void feed(MessageDigest digest, String text) {
        digest.update((text + FIELD).getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java implementation is required to provide SHA-256.
            throw new IllegalStateException(impossible);
        }
    }
}
