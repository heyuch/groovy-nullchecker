package io.github.heyuch.groovy.nullchecker

import groovy.transform.PackageScope
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import static io.github.heyuch.groovy.nullchecker.AstSupport.hasAnno
import static org.codehaus.groovy.ast.ClassHelper.VOID_TYPE

/**
 * What a run actually looked at, so {@code findings = 0} stays interpretable.
 *
 * <p>Counters are the run's, not the checker's: {@link #reset()} starts a new measurement and
 * {@link #snapshot()} is what the harness prints. The {@code @CompileStatic} count matters most
 * because those are the classes a missing registration silently skips.
 */
@PackageScope
class Coverage {

    private static final Set<String> COMPILE_STATIC_ANNOS =
            Collections.unmodifiableSet(['CompileStatic', 'CompileDynamic'] as Set<String>)

    private NullCheckCoverage last = new NullCheckCoverage()

    void reset() {
        last = new NullCheckCoverage()
    }

    NullCheckCoverage snapshot() {
        last
    }

    void cover(ClassNode cn, PackageScopes scopes) {
        if (cn.name.endsWith('.package-info')) {
            return // a package declaration, not a class anyone reads for nullness
        }
        last.classes++
        if (hasAnno(cn, COMPILE_STATIC_ANNOS)) {
            last.compileStaticClasses++
        }
        if (scopes.isAnyNonNullByDefault(cn)) {
            last.nonNullByDefaultClasses++
        }
        last.methodsWithReturnType += cn.methods.count { MethodNode m -> m.returnType != VOID_TYPE }
    }
}
