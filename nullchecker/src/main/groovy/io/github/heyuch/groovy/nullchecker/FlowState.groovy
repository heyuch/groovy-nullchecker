package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.Variable
import org.jspecify.annotations.Nullable

/**
 * Branch-sensitive facts captured at one program point in strict mode.
 *
 * <p>Nullness is a may fact and joins by union: if either branch may be null, the merge may be
 * null. Guards, non-empty collections and present lookups are must facts and join by
 * intersection: they survive only when every reachable predecessor proves them.
 */
@CompileStatic
@PackageScope
class FlowState {

    final Set<Variable> nullableVars

    final Set<Variable> guardedVars

    final Set<Variable> nonEmptyVars

    final Set<String> lookupGuards

    FlowState(Set<Variable> nullableVars, Set<Variable> guardedVars,
              Set<Variable> nonEmptyVars, Set<String> lookupGuards) {
        this.nullableVars = new LinkedHashSet<Variable>(nullableVars)
        this.guardedVars = new LinkedHashSet<Variable>(guardedVars)
        this.nonEmptyVars = new LinkedHashSet<Variable>(nonEmptyVars)
        this.lookupGuards = new LinkedHashSet<String>(lookupGuards)
    }

    FlowState copy() {
        return new FlowState(nullableVars, guardedVars, nonEmptyVars, lookupGuards)
    }

    static @Nullable FlowState join(@Nullable FlowState left, @Nullable FlowState right) {
        if (left == null) {
            return right?.copy()
        }
        if (right == null) {
            return left.copy()
        }
        Set<Variable> nullable = union(left.nullableVars, right.nullableVars)
        Set<Variable> guarded = intersection(left.guardedVars, right.guardedVars)
        Set<Variable> nonEmpty = intersection(left.nonEmptyVars, right.nonEmptyVars)
        Set<String> lookups = intersection(left.lookupGuards, right.lookupGuards)
        return new FlowState(nullable, guarded, nonEmpty, lookups)
    }

    private static <T> Set<T> union(Set<T> left, Set<T> right) {
        Set<T> result = new LinkedHashSet<T>(left)
        result.addAll(right)
        return result
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        Set<T> result = new LinkedHashSet<T>(left)
        result.retainAll(right)
        return result
    }

}
