package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompilationUnitAware
import groovy.transform.CompileStatic
import java.lang.annotation.Annotation
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.jspecify.annotations.Nullable

/**
 * Registers the null checker so that it also covers {@code @CompileStatic} classes.
 *
 * <p>Plain {@link ASTTransformationCustomizer} is not enough, and its failure is silent:
 * a {@code @CompileStatic} class is never checked, so a project whose classes are all
 * statically compiled gets {@code findings = 0} that means "nothing was checked" rather
 * than "nothing is wrong". Verified on Groovy 4.0.30 with two files differing only by a
 * {@code @CompileStatic} line: the dynamic one reports, the static one reports nothing --
 * with and without a source-level {@code @TypeChecked}, and in strict mode too.
 *
 * <p>The fix is to declare the registration phase explicitly. {@code ASTTransformationCustomizer}
 * cannot simply be subclassed for this: its constructor writes {@code annotationNode}
 * through a dynamic property assignment, which throws
 * {@code MissingPropertyException: No such property: annotationNode} as soon as a Groovy
 * subclass is instantiated. Hence composition, not inheritance.
 *
 * <p>Usage, for a build that wants the check inside its normal compilation:
 * <pre>
 * CompilerConfiguration configuration = new CompilerConfiguration()
 * configuration.addCompilationCustomizers(new NullCheckCustomizer())
 * </pre>
 *
 * <p>Prefer {@link io.github.heyuch.groovy.nullchecker.harness.NullCheckCli} for a
 * standalone report: it also proves the checker actually ran (canary plus coverage
 * counters), which a bare customizer cannot.
 */
@CompileStatic
class NullCheckCustomizer extends CompilationCustomizer implements CompilationUnitAware {

    /**
     * Phase at which the registration is declared.
     *
     * <p>{@code CANONICALIZATION} is used because it is the phase in which Groovy's type
     * checking transformations run. It is not the only value that works: measured against
     * a {@code @CompileStatic} class, {@code CONVERSION}, {@code SEMANTIC_ANALYSIS} and
     * {@code CANONICALIZATION} all report, while {@code INSTRUCTION_SELECTION} reports
     * nothing because the type-checking transformation has already been processed by then.
     */
    static final CompilePhase PHASE = CompilePhase.CANONICALIZATION

    private static final Set<String> STATIC_ANNOS = ['CompileStatic', 'TypeChecked'] as Set<String>

    /** Type-checking extension to register. */
    private final String extension

    private final ASTTransformationCustomizer inner

    /** Keeps already-static classes static; adding @TypeChecked changes closure dispatch. */
    private final ASTTransformationCustomizer staticInner

    private final boolean staticOnly

    private @Nullable CompilationUnit unit

    private @Nullable PackageScopes markedScopes

    NullCheckCustomizer() {
        this('io.github.heyuch.groovy.nullchecker.NullChecker')
    }

    NullCheckCustomizer(String extension) {
        this(extension, 'groovy.transform.TypeChecked')
    }

    NullCheckCustomizer(String extension, String annotationClassName) {
        this(extension, annotationClassName, false)
    }

    NullCheckCustomizer(String extension, String annotationClassName, boolean staticOnly) {
        super(PHASE)
        this.staticOnly = staticOnly
        this.extension = extension
        Class<?> annotation
        try {
            annotation = getClass().classLoader.loadClass(annotationClassName)
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "null checker: annotation class not found on the classpath: ${annotationClassName}", e)
        }
        // A String is mandatory here: passing a Class or ClassExpression is silently
        // ignored by the transformation, which produces a green build that checked nothing.
        this.inner = new ASTTransformationCustomizer([extensions: [extension]],
                (Class<? extends Annotation>) annotation)
        this.staticInner = new ASTTransformationCustomizer([extensions: [extension]], CompileStatic)
    }

    /**
     * Forwards the compilation unit to the wrapped customizer.
     *
     * <p>{@code ASTTransformationCustomizer} is {@link CompilationUnitAware} and needs the
     * unit to register its transformation against the right compilation; dropping this
     * forward is a plausible way to get a wrapper that looks right and checks nothing.
     */
    @Override
    void setCompilationUnit(CompilationUnit unit) {
        this.unit = unit
        inner.setCompilationUnit(unit)
        staticInner.setCompilationUnit(unit)
    }

    @Override
    void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        if (staticOnly && !isStaticClass(classNode)) {
            return
        }
        if (isMarkedOnly() && !isMarked(classNode)) {
            return
        }
        if (isCompileStatic(classNode)) {
            staticInner.call(source, context, classNode)
            return
        }
        inner.call(source, context, classNode)
    }

    /** Progressive mode: do not inject type checking into classes outside @NullMarked. */
    private static boolean isMarkedOnly() {
        return 'true'.equalsIgnoreCase(System.getProperty(NullCheckConfig.MARKED_ONLY_PROPERTY))
    }

    private boolean isMarked(ClassNode classNode) {
        PackageScopes scopes = markedScopes
        if (scopes == null) {
            scopes = new PackageScopes(unit, null)
            markedScopes = scopes
        }
        return scopes.isNullMarked(classNode)
    }

    /** A class that already asked for @CompileStatic must stay static during injection. */
    private static boolean isCompileStatic(ClassNode classNode) {
        return AstSupport.hasAnno(classNode, ['CompileStatic'] as Set<String>)
    }

    /** The compile-mode gate: only classes that opted into static checking are analysed. */
    private static boolean isStaticClass(ClassNode classNode) {
        if (io.github.heyuch.groovy.nullchecker.AstSupport.hasAnno(classNode, STATIC_ANNOS) || io.github.heyuch.groovy.nullchecker.AstSupport.hasNullCheckAnno(classNode)) {
            return true
        }
        for (MethodNode method : classNode.methods) {
            if (io.github.heyuch.groovy.nullchecker.AstSupport.hasAnno(method, STATIC_ANNOS) || io.github.heyuch.groovy.nullchecker.AstSupport.hasNullCheckAnno(method)) {
                return true
            }
        }
        return false
    }

    /** The extension class name this customizer registers. */
    String getExtension() {
        return extension
    }

}
