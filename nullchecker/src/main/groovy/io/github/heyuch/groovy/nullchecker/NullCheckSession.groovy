package io.github.heyuch.groovy.nullchecker

import groovy.transform.PackageScope
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.control.CompilationUnit
import static io.github.heyuch.groovy.nullchecker.AstSupport.*
import static org.codehaus.groovy.ast.ClassHelper.VOID_TYPE

/**
 * Everything that belongs to one compilation: configuration, package scopes, the contract
 * registry and coverage.
 *
 * <p>The checker is instantiated once per class, so this state cannot live on it. The session
 * is created on the first class of a compilation and reused for the rest, then dropped when the
 * compilation unit is collected.
 *
 * <p>The reporter is deliberately *not* stored here: a finding must land on the type-checking
 * context of the class currently being visited, so the facade passes its per-instance reporter
 * into each call.
 */
@PackageScope
class NullCheckSession {

    final NullCheckConfig config

    final PackageScopes scopes

    final ContractRegistry contracts

    final Coverage coverage

    NullCheckSession(NullCheckConfig config, CompilationUnit unit, ClassLoader sourceLoader) {
        this.config = config
        this.scopes = new PackageScopes(unit, sourceLoader)
        this.contracts = new ContractRegistry()
        this.coverage = new Coverage()
    }

    void cover(ClassNode cn, NullCheckReporter reporter) {
        reporter.guard("coverage of ${cn.name}") {
            coverage.cover(cn, scopes)
        }
    }

    void check(MethodNode method, NullCheckReporter reporter) {
        reporter.guard("method ${method.declaringClass?.name}#${method.name}") {
            boolean classNonNullByDefault = method.declaringClass != null &&
                    scopes.isReturnValueNonNullByDefault(method.declaringClass)
            boolean methodNonNull = method.returnType != VOID_TYPE &&
                    (hasNonNullAnno(method) || (classNonNullByDefault && !hasNullableAnno(method)))
            if (methodNonNull) {
                def stash = method.getNodeMetaData('INFERRED_NON_NULL_RETURN_VIOLATIONS' /* PORT: marker absent in Groovy 4 */)
                if (stash instanceof List) {
                    stash.each { node ->
                        reporter.report("Cannot return null from @NonNull method '${method.name}'", node)
                    }
                }
            }
            Set<Variable> initialNullable = method.parameters.findAll { hasNullableAnno(it) } as Set<Variable>
            method.code?.visit(new NullCheckVisitor(config.strict, method, methodNonNull, initialNullable,
                    scopes, contracts, reporter, config.containerLookups))
        }
    }

    void checkInitialization(ClassNode cn, NullCheckReporter reporter) {
        reporter.guard("initialization of ${cn.name}") {
            new InitializationCheck(scopes, reporter).check(cn)
        }
    }
}
