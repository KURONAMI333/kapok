package com.kuronami.kapok.mixin.audit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Reads the compiled mixins and asks the classes they aim at whether the things
 * they name are there.
 *
 * <h3>Why this exists</h3>
 * <p>The mixin annotation processor does not run in this build, so a
 * {@code @Shadow} of a field that is not on the target class, an {@code @Inject}
 * into a method that does not exist, or an {@code @At} whose {@code target} is
 * not in the method body all compile cleanly and only fail when the game applies
 * the mixin. Two of those reached a development client before anything noticed.
 * This walks the same configs the loader walks, and compares what they
 * name against the bytecode the build compiled against.
 *
 * <h3>What it does not do</h3>
 * <p>It never loads a class it does not have to. An {@code @At} target names an
 * owner that is often a JDK or third party type present in no artifact here, so
 * the check is made against the instruction list of the target method rather
 * than by resolving the owner. Only {@code @Mixin} targets and the classes a
 * {@code @Shadow} or accessor declares against are read.
 *
 * <p>Every item it looks at produces a line, including the ones it cannot check.
 * An item that is quietly skipped would be indistinguishable from one that
 * passed, which is the failure this was written to remove.
 */
public final class MixinAudit {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";

    /** One thing the audit looked at, and what it found. */
    public record Finding(String config, String mixin, String category, String item, boolean ok, String detail) {

        @Override
        public String toString() {
            return config + " -> " + mixin + " -> " + item + " -> " + (ok ? "OK" : "NG") + " : " + detail;
        }
    }

    /**
     * One mixin config, already read.
     *
     * @param name           the config's file name, for the report
     * @param pkg            its {@code package}
     * @param mixins         every entry of {@code mixins}, {@code client} and {@code server}
     * @param defaultRequire {@code injectors.defaultRequire}, which stands in wherever
     *                       an injector does not write {@code require} for itself
     */
    public record ConfigSpec(String name, String pkg, List<String> mixins, int defaultRequire) {}

    private final ClassLookup mixinClasses;
    private final ClassLookup targetClasses;
    private final List<Finding> findings = new ArrayList<>();

    public MixinAudit(ClassLookup mixinClasses, ClassLookup targetClasses) {
        this.mixinClasses = mixinClasses;
        this.targetClasses = targetClasses;
    }

    public List<Finding> run(List<ConfigSpec> configs) {
        for (ConfigSpec config : configs) {
            for (String entry : config.mixins()) {
                String internal = config.pkg().replace('.', '/') + '/' + entry.replace('.', '/');
                ClassLookup.Found found = mixinClasses.find(internal);
                if (found == null) {
                    add(config, entry, "class", "mixin class " + internal, false,
                            "not compiled — the config names a class that is not in the build output");
                    continue;
                }
                auditMixin(config, entry, found.node());
            }
        }
        return List.copyOf(findings);
    }

    // ---------------------------------------------------------------- mixin

    private void auditMixin(ConfigSpec config, String entry, ClassNode mixin) {
        AnnotationNode mixinAnnotation = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, MIXIN);
        if (mixinAnnotation == null) {
            add(config, entry, "@Mixin", "@Mixin", false, "the class carries no @Mixin annotation");
            return;
        }

        Set<String> targetNames = new LinkedHashSet<>();
        for (Object value : list(get(mixinAnnotation, "value"))) {
            targetNames.add(((Type) value).getInternalName());
        }
        for (Object value : list(get(mixinAnnotation, "targets"))) {
            targetNames.add(((String) value).replace('.', '/'));
        }
        if (targetNames.isEmpty()) {
            add(config, entry, "@Mixin", "@Mixin", false, "names no target class");
            return;
        }

        List<ClassNode> targets = new ArrayList<>();
        for (String name : targetNames) {
            ClassLookup.Found found = targetClasses.find(name);
            if (found == null) {
                add(config, entry, "@Mixin", "@Mixin target " + name, false,
                        "no class by that name on the compile classpath");
            } else {
                add(config, entry, "@Mixin", "@Mixin target " + name, true, "found in " + found.origin());
                targets.add(found.node());
            }
        }
        if (targets.isEmpty()) {
            return;
        }

        for (FieldNode field : mixin.fields) {
            AnnotationNode shadow = annotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW);
            if (shadow != null) {
                checkShadowField(config, entry, targets, field);
            }
        }

        for (MethodNode method : mixin.methods) {
            for (AnnotationNode annotation : both(method.visibleAnnotations, method.invisibleAnnotations)) {
                switch (annotation.desc) {
                    case SHADOW -> checkShadowMethod(config, entry, targets, method, annotation);
                    case ACCESSOR -> checkAccessor(config, entry, targets, method, annotation);
                    case INVOKER -> checkInvoker(config, entry, targets, method, annotation);
                    default -> {
                        if (isInjector(annotation)) {
                            checkInjector(config, entry, targets, method, annotation);
                        }
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- shadow

    private void checkShadowField(ConfigSpec config, String entry, List<ClassNode> targets, FieldNode field) {
        for (ClassNode target : targets) {
            String item = "@Shadow field " + field.name + " " + field.desc + " on " + target.name;
            FieldNode declared = null;
            for (FieldNode candidate : target.fields) {
                if (candidate.name.equals(field.name)) {
                    declared = candidate;
                    break;
                }
            }
            if (declared == null) {
                add(config, entry, "@Shadow", item, false,
                        "not declared on the target" + inheritedNote(target, field.name, true));
            } else if (!declared.desc.equals(field.desc)) {
                add(config, entry, "@Shadow", item, false,
                        "declared with a different type: the target has " + declared.desc);
            } else {
                add(config, entry, "@Shadow", item, true, "declared on the target");
            }
        }
    }

    private void checkShadowMethod(ConfigSpec config, String entry, List<ClassNode> targets,
            MethodNode method, AnnotationNode shadow) {

        String prefix = (String) get(shadow, "prefix");
        String name = method.name;
        if (prefix != null && name.startsWith(prefix)) {
            name = name.substring(prefix.length());
        } else if (prefix == null && name.startsWith("shadow$")) {
            name = name.substring("shadow$".length());
        }

        for (ClassNode target : targets) {
            String item = "@Shadow method " + name + method.desc + " on " + target.name;
            boolean sameName = false;
            boolean exact = false;
            for (MethodNode candidate : target.methods) {
                if (candidate.name.equals(name)) {
                    sameName = true;
                    if (candidate.desc.equals(method.desc)) {
                        exact = true;
                        break;
                    }
                }
            }
            if (exact) {
                add(config, entry, "@Shadow", item, true, "declared on the target");
            } else if (sameName) {
                add(config, entry, "@Shadow", item, false,
                        "the target declares that name with a different descriptor");
            } else {
                add(config, entry, "@Shadow", item, false,
                        "not declared on the target" + inheritedNote(target, name, false));
            }
        }
    }

    /**
     * Says so when a shadowed member is on a supertype rather than the target.
     *
     * <p>That is the shape of the two defects the ninth wave only found on a
     * running client: a {@code @Shadow} resolves against the declaring class
     * alone, so a member the target merely inherits reads as present to a human
     * and is absent to Mixin.
     */
    private String inheritedNote(ClassNode target, String name, boolean field) {
        String superName = target.superName;
        int guard = 0;
        while (superName != null && guard++ < 32) {
            ClassLookup.Found found = targetClasses.find(superName);
            if (found == null) {
                return "";
            }
            ClassNode node = found.node();
            if (field) {
                for (FieldNode candidate : node.fields) {
                    if (candidate.name.equals(name)) {
                        return " — it is declared on the supertype " + node.name
                                + ", which @Shadow does not reach";
                    }
                }
            } else {
                for (MethodNode candidate : node.methods) {
                    if (candidate.name.equals(name)) {
                        return " — it is declared on the supertype " + node.name
                                + ", which @Shadow does not reach";
                    }
                }
            }
            superName = node.superName;
        }
        return "";
    }

    // ------------------------------------------------------ accessor/invoker

    private void checkAccessor(ConfigSpec config, String entry, List<ClassNode> targets,
            MethodNode method, AnnotationNode accessor) {

        String named = (String) get(accessor, "value");
        String name = named != null && !named.isEmpty() ? named : inferred(method.name, "get", "set", "is");
        for (ClassNode target : targets) {
            String item = "@Accessor " + method.name + " -> field " + name + " on " + target.name;
            FieldNode declared = null;
            for (FieldNode candidate : target.fields) {
                if (candidate.name.equals(name)) {
                    declared = candidate;
                    break;
                }
            }
            if (declared == null) {
                add(config, entry, "@Accessor", item, false,
                        "no such field on the target" + inheritedNote(target, name, true));
            } else {
                add(config, entry, "@Accessor", item, true, "declared on the target as " + declared.desc);
            }
        }
    }

    private void checkInvoker(ConfigSpec config, String entry, List<ClassNode> targets,
            MethodNode method, AnnotationNode invoker) {

        String named = (String) get(invoker, "value");
        String name = named != null && !named.isEmpty() ? named : inferred(method.name, "call", "invoke", "new");

        for (ClassNode target : targets) {
            if ("<init>".equals(name)) {
                // A constructor invoker declares the target's arguments and returns
                // the target itself, so the constructor it needs returns void.
                String wanted = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(method.desc));
                String item = "@Invoker " + method.name + " -> <init>" + wanted + " on " + target.name;
                boolean present = target.methods.stream()
                        .anyMatch(m -> m.name.equals("<init>") && m.desc.equals(wanted));
                add(config, entry, "@Invoker", item, present,
                        present ? "declared on the target" : "the target declares no constructor with those arguments");
                continue;
            }
            String item = "@Invoker " + method.name + " -> " + name + method.desc + " on " + target.name;
            MethodNode declared = null;
            for (MethodNode candidate : target.methods) {
                if (candidate.name.equals(name)) {
                    declared = candidate;
                    if (candidate.desc.equals(method.desc)) {
                        break;
                    }
                }
            }
            if (declared == null) {
                add(config, entry, "@Invoker", item, false,
                        "no such method on the target" + inheritedNote(target, name, false));
            } else {
                add(config, entry, "@Invoker", item, true, "declared on the target as " + declared.desc);
            }
        }
    }

    private static String inferred(String methodName, String... prefixes) {
        for (String prefix : prefixes) {
            if (methodName.length() > prefix.length() && methodName.startsWith(prefix)
                    && Character.isUpperCase(methodName.charAt(prefix.length()))) {
                return Character.toLowerCase(methodName.charAt(prefix.length()))
                        + methodName.substring(prefix.length() + 1);
            }
        }
        return methodName;
    }

    // ------------------------------------------------------------- injectors

    /**
     * An injector is anything carrying a {@code method} of strings.
     *
     * <p>Read that way rather than from a list of annotation names, so a mixin
     * that starts using another MixinExtras injector is audited the day it is
     * written instead of the day someone remembers to widen a list here.
     */
    private static boolean isInjector(AnnotationNode annotation) {
        Object method = get(annotation, "method");
        return method instanceof List<?> values && values.stream().allMatch(v -> v instanceof String);
    }

    private void checkInjector(ConfigSpec config, String entry, List<ClassNode> targets,
            MethodNode handler, AnnotationNode annotation) {

        String kind = "@" + simpleName(annotation.desc);
        Integer require = (Integer) get(annotation, "require");
        Integer allow = (Integer) get(annotation, "allow");
        int effectiveRequire = require != null ? require : config.defaultRequire();

        for (Object selectorValue : list(get(annotation, "method"))) {
            String selector = (String) selectorValue;
            for (ClassNode target : targets) {
                String item = kind + " " + handler.name + " method=\"" + selector + "\" on " + target.name;
                if (selector.indexOf('*') >= 0 || selector.startsWith("@") || selector.indexOf('{') >= 0) {
                    add(config, entry, kind, item, true,
                            "unchecked: the selector is a pattern, not a plain member name");
                    continue;
                }
                Member wanted = Member.parse(selector);
                List<MethodNode> matched = new ArrayList<>();
                for (MethodNode candidate : target.methods) {
                    if (wanted.matchesMethod(candidate)) {
                        matched.add(candidate);
                    }
                }
                if (matched.isEmpty()) {
                    add(config, entry, kind, item, false, "the target declares no such method");
                    continue;
                }
                add(config, entry, kind, item, true, matched.size() == 1
                        ? "one method matches: " + matched.get(0).name + matched.get(0).desc
                        : matched.size() + " methods match the bare name — every one of them is injected into");

                if ("@Inject".equals(kind)) {
                    checkInjectCallback(config, entry, handler, target, matched);
                }
                checkAt(config, entry, kind, handler, annotation, target, matched, effectiveRequire, allow);
                checkLocals(config, entry, kind, handler, target, matched);
            }
        }
    }

    /**
     * {@code @Inject} requires {@code CallbackInfo} for a void target and
     * {@code CallbackInfoReturnable} for every other target. javac cannot
     * check this; Mixin rejects it only while the game is starting.
     */
    private void checkInjectCallback(ConfigSpec config, String entry, MethodNode handler,
            ClassNode target, List<MethodNode> matched) {
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        for (MethodNode method : matched) {
            String expected = Type.getReturnType(method.desc).equals(Type.VOID_TYPE)
                    ? "org.spongepowered.asm.mixin.injection.callback.CallbackInfo"
                    : "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable";
            String item = "@Inject " + handler.name + " callback on " + target.name + "."
                    + method.name + method.desc;
            boolean found = false;
            for (Type argument : handlerArgs) {
                if (expected.equals(argument.getClassName())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                add(config, entry, "@Inject", item, false,
                        "callback type must be " + expected + " for this target return type");
            } else {
                add(config, entry, "@Inject", item, true, "callback type is " + expected);
            }
        }
    }

    private void checkAt(ConfigSpec config, String entry, String kind, MethodNode handler,
            AnnotationNode annotation, ClassNode target, List<MethodNode> matched,
            int require, Integer allow) {

        List<AnnotationNode> ats = new ArrayList<>();
        Object at = get(annotation, "at");
        if (at instanceof AnnotationNode single) {
            ats.add(single);
        } else if (at instanceof List<?> many) {
            for (Object one : many) {
                ats.add((AnnotationNode) one);
            }
        }

        if (ats.isEmpty()) {
            String why = get(annotation, "constant") != null
                    ? "unchecked: @Constant names a value, not an instruction the audit can look up"
                    : "unchecked: this injector carries no @At";
            add(config, entry, kind, kind + " " + handler.name + " injection point on " + target.name, true, why);
            return;
        }

        for (AnnotationNode point : ats) {
            String value = enumName(get(point, "value"));
            String rawTarget = (String) get(point, "target");
            Integer ordinal = (Integer) get(point, "ordinal");
            String head = kind + " " + handler.name + " @At(\"" + value + "\""
                    + (rawTarget == null ? "" : ", target=\"" + rawTarget + "\"") + ") on " + target.name;

            if (rawTarget == null || rawTarget.isEmpty()) {
                add(config, entry, "@At", head, true,
                        "unchecked: \"" + value + "\" is a position in the method, not a named member");
                continue;
            }
            if (!("INVOKE".equals(value) || "INVOKE_ASSIGN".equals(value) || "FIELD".equals(value))) {
                add(config, entry, "@At", head, true, "unchecked: the audit reads INVOKE, INVOKE_ASSIGN and FIELD");
                continue;
            }
            if (rawTarget.indexOf('*') >= 0) {
                add(config, entry, "@At", head, true, "unchecked: the target is a pattern");
                continue;
            }

            Member wanted = Member.parse(rawTarget);
            boolean field = "FIELD".equals(value);
            for (MethodNode method : matched) {
                int hits = 0;
                for (AbstractInsnNode insn : method.instructions) {
                    if (field) {
                        if (insn instanceof FieldInsnNode f && wanted.matchesField(f)) {
                            hits++;
                        }
                    } else if (insn instanceof MethodInsnNode m && wanted.matchesInvoke(m)) {
                        hits++;
                    }
                }
                String where = head + " in " + method.name + method.desc;
                if (hits == 0) {
                    add(config, entry, "@At", where, false, "no instruction in the method body matches");
                    continue;
                }
                if (ordinal != null && ordinal >= 0 && ordinal >= hits) {
                    add(config, entry, "@At", where, false,
                            "ordinal " + ordinal + " is out of range: only " + hits + " instruction(s) match");
                    continue;
                }
                int selected = ordinal != null && ordinal >= 0 ? 1 : hits;
                if (selected < require) {
                    add(config, entry, "@At", where, false,
                            "require=" + require + " but only " + selected + " injection(s) would be made");
                } else if (allow != null && selected > allow) {
                    add(config, entry, "@At", where, false,
                            "allow=" + allow + " but " + selected + " instructions match — the target has multiplied");
                } else {
                    add(config, entry, "@At", where, true, hits + " matching instruction(s), require=" + require
                            + (allow == null ? ", no allow" : ", allow=" + allow)
                            + (ordinal == null ? "" : ", ordinal=" + ordinal));
                }
            }
        }
    }

    /**
     * Checks the {@code @Local(argsOnly = true)} parameters of a handler.
     *
     * <p>{@code argsOnly} says the value is one of the target method's own
     * arguments, so the type has to be among them. This is the check that costs
     * a start-up rather than a miss when it is wrong: a sugar that cannot be
     * resolved fails while the mixin is applied.
     */
    private void checkLocals(ConfigSpec config, String entry, String kind, MethodNode handler,
            ClassNode target, List<MethodNode> matched) {

        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        forEachParameterAnnotation(handler, (parameter, annotation) -> {
            if (!LOCAL.equals(annotation.desc) || parameter >= handlerArgs.length) {
                return;
            }
            Type wanted = handlerArgs[parameter];
            boolean argsOnly = Boolean.TRUE.equals(get(annotation, "argsOnly"));
            String extra = get(annotation, "index") != null || get(annotation, "name") != null
                    ? " (index/name itself is not checked)" : "";
            for (MethodNode method : matched) {
                String item = kind + " " + handler.name + " @Local " + wanted.getClassName()
                        + " (parameter " + parameter + ") in " + target.name + "." + method.name + method.desc;
                if (!argsOnly) {
                    add(config, entry, "@Local", item, true,
                            "unchecked: without argsOnly the value comes from the local variable table");
                    continue;
                }
                boolean present = false;
                for (Type arg : Type.getArgumentTypes(method.desc)) {
                    if (arg.equals(wanted)) {
                        present = true;
                        break;
                    }
                }
                add(config, entry, "@Local", item, present,
                        present ? "the target takes that type as an argument" + extra
                                : "the target takes no argument of that type" + extra);
            }
        });
    }

    private interface ParameterAnnotationSink {
        void accept(int parameter, AnnotationNode annotation);
    }

    private static void forEachParameterAnnotation(MethodNode method, ParameterAnnotationSink sink) {
        int arguments = Type.getArgumentTypes(method.desc).length;
        feed(method.visibleParameterAnnotations, method.visibleAnnotableParameterCount, arguments, sink);
        feed(method.invisibleParameterAnnotations, method.invisibleAnnotableParameterCount, arguments, sink);
    }

    private static void feed(List<AnnotationNode>[] table, int annotable, int arguments,
            ParameterAnnotationSink sink) {

        if (table == null) {
            return;
        }
        // The attribute may cover fewer parameters than the descriptor declares,
        // in which case it covers the trailing ones — javac leaves out synthetic
        // leading arguments. Nothing in this repository hits that, but reading the
        // index as if it always started at zero is the kind of quiet mistake this
        // whole class exists to stop.
        int offset = annotable > 0 && annotable < arguments ? arguments - annotable : 0;
        for (int i = 0; i < table.length; i++) {
            if (table[i] == null) {
                continue;
            }
            for (AnnotationNode annotation : table[i]) {
                sink.accept(i + offset, annotation);
            }
        }
    }

    // ------------------------------------------------------------ selectors

    /**
     * A member as a mixin selector names one: {@code Lowner;name(args)ret} for a
     * method, {@code Lowner;name:Ltype;} for a field, with the owner and the
     * descriptor each optional.
     */
    record Member(String owner, String name, String desc) {

        static Member parse(String raw) {
            String text = raw.trim();
            String owner = null;
            if (text.startsWith("L")) {
                int end = text.indexOf(';');
                int open = text.indexOf('(');
                if (end > 0 && (open < 0 || end < open)) {
                    owner = text.substring(1, end);
                    text = text.substring(end + 1);
                }
            }
            int open = text.indexOf('(');
            if (open >= 0) {
                return new Member(owner, text.substring(0, open), text.substring(open));
            }
            int colon = text.indexOf(':');
            if (colon >= 0) {
                return new Member(owner, text.substring(0, colon), text.substring(colon + 1));
            }
            return new Member(owner, text, null);
        }

        boolean matchesMethod(MethodNode method) {
            return method.name.equals(name) && (desc == null || method.desc.equals(desc));
        }

        boolean matchesInvoke(MethodInsnNode insn) {
            return insn.name.equals(name)
                    && (owner == null || insn.owner.equals(owner))
                    && (desc == null || insn.desc.equals(desc));
        }

        boolean matchesField(FieldInsnNode insn) {
            return insn.name.equals(name)
                    && (owner == null || insn.owner.equals(owner))
                    && (desc == null || insn.desc.equals(desc));
        }
    }

    // ---------------------------------------------------------------- plumbing

    private void add(ConfigSpec config, String mixin, String category, String item, boolean ok, String detail) {
        findings.add(new Finding(config.name(), mixin, category, item, ok, detail));
    }

    private static List<AnnotationNode> both(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> out = new ArrayList<>();
        if (visible != null) {
            out.addAll(visible);
        }
        if (invisible != null) {
            out.addAll(invisible);
        }
        return out;
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible,
            String desc) {

        for (AnnotationNode annotation : both(visible, invisible)) {
            if (annotation.desc.equals(desc)) {
                return annotation;
            }
        }
        return null;
    }

    /** @return the value of one annotation element, or null when it is not written. */
    static Object get(AnnotationNode annotation, String key) {
        List<Object> values = annotation.values;
        if (values == null) {
            return null;
        }
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if (key.equals(values.get(i))) {
                return values.get(i + 1);
            }
        }
        return null;
    }

    private static List<?> list(Object value) {
        if (value == null) {
            return List.of();
        }
        return value instanceof List<?> many ? many : List.of(value);
    }

    /** ASM hands an enum back as a two element array of descriptor and constant name. */
    private static String enumName(Object value) {
        if (value instanceof String[] pair && pair.length == 2) {
            return pair[1];
        }
        return String.valueOf(value);
    }

    private static String simpleName(String descriptor) {
        String name = descriptor.substring(1, descriptor.length() - 1);
        return name.substring(name.lastIndexOf('/') + 1);
    }
}
