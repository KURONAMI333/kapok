package com.kuronami.kapokcore;

import cpw.mods.jarhandling.SecureJar;
import java.nio.file.Path;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileInfoParser;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * A {@code ModFile} that answers {@code compileContent} from {@link ScanCache} when it
 * can.
 *
 * <p>Subclassing is the only way in. {@code DiscoveryPipeline.addModFile} rejects
 * anything that is not a {@code ModFile} with a technical error, so a fresh
 * {@code IModFile} implementation cannot be handed back; and {@code compileContent} is
 * public, non-final, and called from exactly one place -
 * {@code BackgroundScanHandler.submitForScanning} - so overriding it substitutes the scan
 * without changing when or on which thread it happens.
 *
 * <p>Nothing but the override lives here. {@code ModFile}'s constructor finishes by
 * calling {@code ModFileParser.readModList}, which runs a parser against a
 * half-constructed object before any subclass field has been assigned, so a subclass that
 * put behaviour in a method the parser reaches would see nulls. Both fields below are
 * read only from {@code compileContent}, which the loader calls much later, and are
 * checked for null anyway.
 */
final class CachingModFile extends ModFile {
    private final ScanCache cache;
    private final Path jar;

    /**
     * This mod's file name inside {@link #jar}, when it ships inside another mod, and null
     * when {@code jar} is its own file.
     *
     * <p>Resolved by the reader and handed over finished, rather than read back out of
     * {@code getDiscoveryAttributes()} here. Two reasons, and either alone is enough:
     * the attributes are held in a field the loader can replace after construction, so
     * what this class read later would not have to be what the reader decided; and the
     * superclass constructor runs a parser against this object before any field below is
     * assigned, so anything the parser could reach must not depend on them.
     */
    private final String nestedName;

    /** For a jar with a {@code neoforge.mods.toml}; the type comes from the manifest. */
    CachingModFile(SecureJar jar, ModFileInfoParser parser, ModFileDiscoveryAttributes attributes,
            ScanCache cache, Path path, String nestedName) {
        super(jar, parser, attributes);
        this.cache = cache;
        this.jar = path;
        this.nestedName = nestedName;
    }

    /** For a jar that declares its type in the manifest instead. */
    CachingModFile(SecureJar jar, ModFileInfoParser parser, IModFile.Type type,
            ModFileDiscoveryAttributes attributes, ScanCache cache, Path path, String nestedName) {
        super(jar, parser, type, attributes);
        this.cache = cache;
        this.jar = path;
        this.nestedName = nestedName;
    }

    @Override
    public ModFileScanData compileContent() {
        return scan();
    }

    private ModFileScanData scan() {
        ScanCache reader = this.cache;
        if (reader == null || jar == null) {
            return super.compileContent();
        }
        try {
            return reader.compile(jar, nestedName, this::getModFileInfo, super::compileContent);
        } catch (LinkageError unreachable) {
            // The constructor already proved this class links, but the method references
            // above are bound by an invokedynamic that resolves the first time this runs -
            // on a background scanning thread, outside the reader's own handler. A failure
            // there would be reported as this mod being broken, which it is not. The call
            // below needs no bootstrap, and a bootstrap that failed did so before the scan.
            return super.compileContent();
        }
    }
}
