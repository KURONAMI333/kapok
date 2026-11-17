package com.kuronami.kapokcore.jij;

import com.kuronami.kapokcore.Notes;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.neoforged.fml.loading.moddiscovery.locators.JarInJarDependencyLocator;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import sun.misc.Unsafe;

/**
 * Puts {@link KapokJarInJarLocator} into mod discovery in place of the loader's own
 * Jar-in-Jar locator.
 *
 * <p>Replacing rather than adding, because the two do the same job: a second locator
 * would find the same nested jars a second time and the loader would report every one of
 * them as a duplicate.
 *
 * <p>The list to change is {@code ModDiscoverer.dependencyLocators}, which is private,
 * final and built once in the constructor. It is read exactly once, after every candidate
 * locator has run, and this is called from one of those - so there is a window in which
 * the list can be exchanged and no reader has seen either version yet.
 * {@code fancymodloader} is an automatic module and therefore open to everything, so
 * {@code setAccessible} is expected to work; {@code Unsafe} is kept as the second way in
 * when a module refuses reflection, and both being refused is a reason to stand down
 * rather than to try anything further.
 *
 * <p>Standing down costs a launch its heap saving and nothing else. The loader's own
 * locator is still in the list, mounts the nested jars where they lie, and the game runs
 * exactly as it would with this jar absent - so every failure here lands on one INFO line
 * and no exception.
 */
public final class JarInJarSwap {
    /** {@code -Dkapok.core.jij=off} to leave mod discovery alone. */
    public static final String PROPERTY = "kapok.core.jij";

    /**
     * Directory under {@code .cache/kapok-core/} holding the copies.
     *
     * <p>Not called {@code jij}: that is the scheme of the URI the loader mounts a nested
     * jar through, and these paths are handed back to the loader and to every mod that
     * asks where it was loaded from. A directory whose name reads like a filesystem
     * scheme in somebody else's log is a thing to have to explain later.
     */
    static final String DIRECTORY = "nested";

    private final Notes notes;
    private final AtomicBoolean attempted = new AtomicBoolean();

    public JarInJarSwap(Notes notes) {
        this.notes = notes;
    }

    /**
     * Tries once per launch, whatever happens.
     *
     * @param pipeline the pipeline handed to a candidate locator. It is an inner class of
     *                 the discoverer that owns the list, which is the only way to reach
     *                 that instance from here.
     * @param cacheDirectory {@code .cache/kapok-core} under the game directory, or null
     *                       when the game directory is not known - in which case there is
     *                       nowhere to write the copies and nothing to install
     */
    public void installOnce(IDiscoveryPipeline pipeline, Path cacheDirectory) {
        if (!attempted.compareAndSet(false, true)) {
            return;
        }
        try {
            install(pipeline, cacheDirectory);
        } catch (Throwable t) {
            // Includes the linkage failures that cannot happen on a classpath but can in
            // a module graph: this class names loader types directly.
            notes.trouble("Kapok Core: nested jars left in memory (" + t + ")", t);
        }
    }

    private void install(IDiscoveryPipeline pipeline, Path cacheDirectory) throws Exception {
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY))) {
            notes.info("Kapok Core: nested jars left in memory (-D" + PROPERTY + "=off)");
            return;
        }
        if (cacheDirectory == null) {
            notes.info("Kapok Core: nested jars left in memory (the game directory is not known)");
            return;
        }
        Object discoverer = enclosing(pipeline);
        if (discoverer == null) {
            return;
        }
        Field field = field(discoverer.getClass(), "dependencyLocators");
        if (field == null) {
            notes.info("Kapok Core: nested jars left in memory (" + discoverer.getClass().getName()
                    + " has no dependencyLocators)");
            return;
        }
        Object current = read(discoverer, field);
        if (!(current instanceof List<?> locators)) {
            notes.info("Kapok Core: nested jars left in memory (mod discovery does not hold"
                    + " a list of dependency locators)");
            return;
        }

        NestedJarStore store = new NestedJarStore(cacheDirectory.resolve(DIRECTORY), notes);
        List<IDependencyLocator> replacement = new ArrayList<>(locators.size());
        int replaced = 0;
        for (Object each : locators) {
            if (each != null && each.getClass() == JarInJarDependencyLocator.class) {
                replacement.add(new KapokJarInJarLocator(store, notes));
                replaced++;
            } else if (each instanceof IDependencyLocator kept) {
                replacement.add(kept);
            } else {
                notes.info("Kapok Core: nested jars left in memory (an unexpected entry in"
                        + " the dependency locator list)");
                return;
            }
        }
        if (replaced != 1) {
            // The exact class, not a subclass: somebody else's subclass is somebody
            // else's behaviour, and taking it out would remove whatever it was doing.
            // Zero of them means the loader no longer ships the class this one extends,
            // which makes the subclass meaningless here; more than one is a shape nobody
            // has seen and not one to guess at.
            notes.info("Kapok Core: nested jars left in memory (" + replaced
                    + " jar-in-jar locators in mod discovery, expected 1)");
            return;
        }

        if (!write(discoverer, field, List.copyOf(replacement))) {
            return;
        }
        if (!(read(discoverer, field) instanceof List<?> now)
                || now.stream().noneMatch(each -> each instanceof KapokJarInJarLocator)) {
            // A field that took the write and does not read back is one we are not in.
            notes.info("Kapok Core: nested jars left in memory (mod discovery did not keep"
                    + " the replacement)");
            return;
        }
        if (!notes.info("Kapok Core: jar-in-jar locator replaced (nested jars are written"
                + " to .cache/kapok-core/nested and read from there)")) {
            // The rule the other two announcements follow. This jar's only evidence that
            // it changed anything is its own INFO output, and a launch where discovery was
            // rearranged without anybody able to tell is not one to run.
            write(discoverer, field, locators);
        }
    }

    /** @return the {@code ModDiscoverer} the pipeline belongs to, or null when unreachable */
    private Object enclosing(IDiscoveryPipeline pipeline) {
        Field link = field(pipeline.getClass(), "this$0");
        if (link == null) {
            notes.info("Kapok Core: nested jars left in memory (the discovery pipeline is"
                    + " not the loader's own)");
            return null;
        }
        Object discoverer = read(pipeline, link);
        if (discoverer == null) {
            notes.info("Kapok Core: nested jars left in memory (the discovery pipeline names"
                    + " no discoverer)");
        }
        return discoverer;
    }

    private Field field(Class<?> owner, String name) {
        for (Class<?> each = owner; each != null; each = each.getSuperclass()) {
            try {
                return each.getDeclaredField(name);
            } catch (NoSuchFieldException keepLooking) {
                // A subclass of the pipeline, or a renamed field. The loop answers the
                // first case and the caller stands down for the second.
            }
        }
        return null;
    }

    private Object read(Object owner, Field field) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable refused) {
            Unsafe unsafe = theUnsafe();
            if (unsafe == null) {
                return null;
            }
            try {
                Object value = unsafe.getObject(owner, unsafe.objectFieldOffset(field));
                secondWayIn("read", field, refused);
                return value;
            } catch (Throwable t) {
                notes.trouble("Kapok Core: nested jars left in memory (could not read "
                        + field.getName() + ")", t);
                return null;
            }
        }
    }

    /** @return true when the field now holds {@code value} */
    private boolean write(Object owner, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(owner, value);
            return true;
        } catch (Throwable refused) {
            Unsafe unsafe = theUnsafe();
            if (unsafe == null) {
                return false;
            }
            try {
                unsafe.putObject(owner, unsafe.objectFieldOffset(field), value);
                secondWayIn("replace", field, refused);
                return true;
            } catch (Throwable t) {
                notes.trouble("Kapok Core: nested jars left in memory (could not replace "
                        + field.getName() + ")", t);
                return false;
            }
        }
    }

    /**
     * Says that the reflective way in was refused and the other one worked.
     *
     * <p>Both ways are expected to work here, so a launch that needed the second one is a
     * launch where the module graph is not what this was built against - which is worth
     * knowing when it eventually stops working altogether, and worth nothing to a user
     * whose game is running. Hence a debug line and not an INFO one.
     */
    private void secondWayIn(String what, Field field, Throwable refused) {
        if (notes.detailed()) {
            notes.detail("Kapok Core: had to " + what + " " + field.getName()
                    + " through sun.misc.Unsafe (" + refused + ")");
        }
    }

    private Unsafe theUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            // jdk.unsupported opens sun.misc to every module, so this is a supported call.
            field.setAccessible(true);
            return field.get(null) instanceof Unsafe found ? found : null;
        } catch (Throwable t) {
            notes.trouble("Kapok Core: nested jars left in memory (sun.misc.Unsafe is not"
                    + " available)", t);
            return null;
        }
    }
}
