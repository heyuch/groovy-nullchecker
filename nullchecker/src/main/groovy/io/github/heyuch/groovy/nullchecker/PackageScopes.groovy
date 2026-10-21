package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.control.CompilationUnit
import static io.github.heyuch.groovy.nullchecker.AstSupport.*

/**
 * Package-level nullness defaults for one compilation.
 *
 * <p>Owns the three sources a package default can come from -- a source {@code package-info}
 * in this compilation, the package node of a class, and a precompiled {@code package-info.class}
 * on the classpath -- plus the caches that make resolving them affordable. One instance per
 * compilation; the caches are instance state, so nothing leaks into the next compilation.
 */
@CompileStatic
@PackageScope
class PackageScopes {

    private final ClassLoader transformLoader

    private final ClassLoader sourceLoader

    private final Map<String, List<String>> sourceAnnotations = [:]

    private final Map<String, List<String>> byClass = [:]

    private final Map<String, List<String>> byPackage = [:]

    private List<ClassLoader> classLoaders

    PackageScopes(CompilationUnit unit, ClassLoader sourceLoader) {
        this.transformLoader = unit?.transformLoader
        this.sourceLoader = sourceLoader
        sourceAnnotations.putAll(collectSourceAnnotations(unit))
    }

    /**
     * Defaults that apply to parameters only. {@code @NonNullApi} is Spring's package-level
     * marking and covers returns as well, so it appears in both sets.
     */
    private static final Set<String> PARAMETER_DEFAULT_ANNOS = Collections.unmodifiableSet([
            'ParametersAreNonnullByDefault', 'ParametersAreNonNullByDefault', 'NonNullApi'] as Set)

    /** Defaults that apply to return values only. */
    private static final Set<String> RETURN_DEFAULT_ANNOS = Collections.unmodifiableSet([
            'ReturnValuesAreNonnullByDefault', 'NonNullApi'] as Set)

    /** Defaults that apply to fields only. */
    private static final Set<String> FIELD_DEFAULT_ANNOS = Collections.unmodifiableSet(['NonNullFields'] as Set)

    /**
     * Scans the compilation unit for {@code package-info} classes and records the
     * simple names of their annotations.
     *
     * <p>The annotations are read from the {@code package-info} class's
     * <em>{@code PackageNode}</em>, not from the class node itself: for a
     * {@code package-info}, Groovy parses {@code @Foo package a.b;} onto the package
     * and leaves the synthetic class node unannotated. Verified on Groovy 4.0.30 --
     * {@code cn.annotations} is empty while {@code cn.package.annotations} holds the
     * annotation.
     *
     * <p>An instance method because it reads the DSL's {@code context}, which is exposed
     * only on the instance.
     *
     * @return package name to annotation simple names
     */
    private Map<String, List<String>> collectSourceAnnotations(CompilationUnit unit) {
        Map<String, List<String>> found = [:]
        try {
            unit?.AST?.classes?.each { ClassNode cn ->
                if (cn.name.endsWith('.package-info')) {
                    PackageNode pkg = cn.package
                    String pkgName = normalisePackageName(pkg?.name) ?:
                            cn.name.substring(0, cn.name.length() - '.package-info'.length())
                    if (pkgName) {
                        List<String> names = new ArrayList<String>()
                        if (pkg != null) {
                            for (AnnotationNode annotation : pkg.annotations) {
                                names.add(annotation.classNode.nameWithoutPackage)
                            }
                        }
                        found[pkgName] = names
                    }
                }
            }
        } catch (Throwable ignored) {
            // Best effort: an unreachable AST must not break the check itself, it only
            // means package defaults are unavailable and class-level marking applies.
        }
        return found
    }

    /**
     * Determines whether a class is non-null-by-default, honouring JSpecify-style
     * nearest-scope-wins semantics: the class and each enclosing (outer) class are consulted from
     * innermost to outermost, then the package. At each scope an {@code @NullUnmarked}
     * opt-out short-circuits to {@code false} and an {@code @NullMarked}/{@code @NonNullByDefault}
     * marking to {@code true}; scopes with neither are transparent so the search continues outward.
     *
     * <p>The package scope is resolved from three sources, because no single one covers
     * every case:
     * <ol>
     *   <li>{@link #sourceAnnotations}, collected from a source {@code package-info}
     *       in this compilation. Required because Groovy 4 hands each source class its
     *       own {@code PackageNode}, so a source {@code package-info}'s annotations are
     *       not visible on the package node of the classes it applies to.</li>
     *   <li>{@code classNode.package.annotations}. Populated in some situations, but
     *       verified <em>not</em> populated on Groovy 4.0.30 when the {@code
     *       package-info.class} merely sits on the classpath next to a class compiled
     *       in this run, so it cannot be relied on alone.</li>
     *   <li>A reflective lookup of {@code <pkg>.package-info}, which recovers the
     *       package annotations for a precompiled dependency. Limited to
     *       runtime-retained annotations, since it goes through the JVM's package
     *       object; the common nullness annotations (JSpecify, JSR-305, JetBrains,
     *       Checker Framework) all are runtime-retained.</li>
     * </ol>
     *
     * <p>Scope is deliberately the exact package: a package annotation does <em>not</em>
     * apply to subpackages, matching JSpecify's rule that a marking applies to the
     * annotated element itself.
     */
    boolean isNonNullByDefault(ClassNode cn) {
        return isNullDefaultInScope(cn, Collections.emptySet())
    }

    /** Parameter defaults: "@NullMarked"-style plus the parameter-scoped annotations. */
    boolean isParameterNonNullByDefault(ClassNode cn) {
        return isNullDefaultInScope(cn, PARAMETER_DEFAULT_ANNOS)
    }

    /** Return-value defaults: "@NullMarked"-style plus the return-scoped annotations. */
    boolean isReturnValueNonNullByDefault(ClassNode cn) {
        return isNullDefaultInScope(cn, RETURN_DEFAULT_ANNOS)
    }

    /** Field defaults: "@NullMarked"-style plus the field-scoped annotations. */
    boolean isFieldNonNullByDefault(ClassNode cn) {
        return isNullDefaultInScope(cn, FIELD_DEFAULT_ANNOS)
    }

    /** Any default at all, whatever it covers; used for the coverage counter. */
    boolean isAnyNonNullByDefault(ClassNode cn) {
        Set<String> anyScope = new HashSet<String>(PARAMETER_DEFAULT_ANNOS)
        anyScope.addAll(RETURN_DEFAULT_ANNOS)
        anyScope.addAll(FIELD_DEFAULT_ANNOS)
        return isNullDefaultInScope(cn, anyScope)
    }

    /**
     * Resolves whether a non-null default applies to one scope.
     *
     * <p>A default is not one switch but three: JSR-305 and Spring split it into parameters
     * ({@code @ParametersAreNonnullByDefault}), return values
     * ({@code @ReturnValuesAreNonnullByDefault}) and fields ({@code @NonNullFields}), while
     * {@code @NullMarked}/{@code @NonNullByDefault} cover all three. A single boolean could
     * not express that, which is why the parameter-scoped annotations were honoured on a
     * class but silently ignored on a package.
     *
     * <p>Precedence: the class chain is consulted innermost-first and an {@code @NullUnmarked}
     * anywhere on it opts out entirely; package annotations are consulted next. Within one
     * level an opt-out beats a marking.
     *
     * @param cn         class whose defaults are being resolved
     * @param scopeAnnos additional annotation names that count for this scope
     * @return whether the scope's values are non-null by default
     */
    boolean isNullDefaultInScope(ClassNode cn, Set<String> scopeAnnos) {
        Set<String> accepted = new HashSet<String>(NONNULL_BY_DEFAULT_ANNOS)
        accepted.addAll(scopeAnnos)
        for (ClassNode c = cn; c != null; c = c.outerClass) {
            if (hasNullUnmarkedAnno(c)) return false
            if (declaresAny(c, accepted)) return true
        }
        List<String> pkgAnnos = packageAnnotationNames(cn)
        if (pkgAnnos.any { String n -> NULL_UNMARKED_ANNOS.contains(n) }) return false
        return pkgAnnos.any { String n -> accepted.contains(n) }
    }

    /**
     * The simple names of the annotations declared on a class's package, from every source
     * that can carry them: a source {@code package-info} in this compilation, the package
     * node, and a precompiled {@code package-info.class} on the classpath.
     */
    List<String> packageAnnotationNames(ClassNode cn) {
        String cacheKey = cn?.name
        if (cacheKey != null) {
            List<String> cached = byClass.get(cacheKey)
            if (cached != null) return cached
        }
        List<String> names = new ArrayList<String>()
        String pkgName = normalisePackageName(cn?.package?.name)
        List<String> fromSource = pkgName == null ? null : sourceAnnotations[pkgName]
        if (fromSource != null) {
            names.addAll(fromSource)
        }
        PackageNode pkg = cn?.package
        if (pkg != null) {
            for (AnnotationNode annotation : pkg.annotations) {
                names.add(annotation.classNode.nameWithoutPackage)
            }
        }
        List<String> fromClasspath = pkgName == null ? null : lookupPackageAnnotations(pkgName)
        if (fromClasspath != null) {
            names.addAll(fromClasspath)
        }
        if (cacheKey != null) {
            byClass.put(cacheKey, names)
        }
        return names
    }

    /**
     * Reads a precompiled package's annotations through the JVM package object.
     *
     * <p>Only runtime-retained annotations are visible this way, which covers the
     * common nullness annotations. A {@code package-info.class} in a dependency that
     * uses CLASS retention cannot be seen at all; that limitation is documented.
     *
     * <p>Every plausible classloader is tried before giving up; see
     * {@link #candidateClassLoaders()} for why one is not enough.
     *
     * @param packageName fully qualified package name
     * @return annotation simple names, or {@code null} when no package class is loadable
     */
    List<String> lookupPackageAnnotations(String packageName) {
        if (byPackage.containsKey(packageName)) {
            return byPackage.get(packageName)
        }
        String resource = packageName + '.package-info'
        for (ClassLoader loader : candidateClassLoaders()) {
            try {
                Class<?> pkgClass = loader.loadClass(resource)
                List<String> names = pkgClass.getPackage()?.annotations*.annotationType()*.simpleName
                if (names) {
                    byPackage.put(packageName, names)
                    return names
                }
            } catch (Throwable ignored) {
                // this loader cannot see it; try the next one
            }
        }
        byPackage.put(packageName, null)
        return null
    }

    /**
     * Classloaders that might see the analysed project's classpath, most specific first.
     *
     * <p>Several are needed because no single one is right in every setup. Under Gradle
     * the checker is loaded by the build's classloader, while the project's own classes
     * (and its {@code package-info.class}) are visible only to the compiler worker's
     * Groovy classloader -- so using the checker's own classloader alone finds nothing in
     * exactly the configuration most users have.
     */
    List<ClassLoader> candidateClassLoaders() {
        if (classLoaders != null) {
            return classLoaders
        }
        List<ClassLoader> loaders = []
        try {
            loaders << transformLoader
        } catch (Throwable ignored) {
            // not reachable in this setup
        }
        try {
            loaders << sourceLoader
        } catch (Throwable ignored) {
            // not reachable in this setup
        }
        loaders << Thread.currentThread().contextClassLoader
        loaders << PackageScopes.classLoader
        loaders = loaders.findAll { ClassLoader l -> l != null }.unique()
        classLoaders = loaders
        return loaders
    }

    /** Strips the trailing dot some Groovy versions leave on {@code PackageNode#getName()}. */
    static String normalisePackageName(String name) {
        return name == null ? null : name.replaceAll(/\.$/, '')
    }
}
