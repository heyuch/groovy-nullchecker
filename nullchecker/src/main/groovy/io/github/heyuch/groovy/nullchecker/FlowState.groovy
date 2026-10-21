package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.Variable
import org.jspecify.annotations.Nullable

/**
 * Branch-sensitive facts captured at one program point in strict mode.
 *
 * <p>Each variable is either known non-null, known null, maybe-null, or unknown (absent). Join
 * follows the unknown-suppressing policy from NULLNESS-FLOW: an unknown plus a definitely-null
 * path becomes maybe-null, while unknown plus non-null stays unknown. Non-empty collections and
 * present lookups are must facts and join by intersection.
 */
@CompileStatic
@PackageScope
class FlowState {

    final Map<Variable, Nullness> locals

    final Set<Variable> nonEmptyVars

    final Set<String> lookupGuards

    FlowState(Map<Variable, Nullness> locals, Set<Variable> nonEmptyVars, Set<String> lookupGuards) {
        this.locals = new LinkedHashMap<Variable, Nullness>(locals)
        this.nonEmptyVars = new LinkedHashSet<Variable>(nonEmptyVars)
        this.lookupGuards = new LinkedHashSet<String>(lookupGuards)
    }

    FlowState copy() {
        return new FlowState(locals, nonEmptyVars, lookupGuards)
    }

    static @Nullable FlowState join(@Nullable FlowState left, @Nullable FlowState right) {
        if (left == null) {
            return right?.copy()
        }
        if (right == null) {
            return left.copy()
        }
        Set<Variable> variables = new LinkedHashSet<Variable>(left.locals.keySet())
        variables.addAll(right.locals.keySet())
        Map<Variable, Nullness> joined = new LinkedHashMap<Variable, Nullness>()
        for (Variable variable : variables) {
            Nullness leftValue = left.locals[variable] ?: Nullness.UNKNOWN
            Nullness rightValue = right.locals[variable] ?: Nullness.UNKNOWN
            joined.put(variable, joinValue(leftValue, rightValue))
        }
        Set<Variable> nonEmpty = intersection(left.nonEmptyVars, right.nonEmptyVars)
        Set<String> lookups = intersection(left.lookupGuards, right.lookupGuards)
        return new FlowState(joined, nonEmpty, lookups)
    }

    static Nullness joinValue(Nullness left, Nullness right) {
        if (left == Nullness.NON_NULL && right == Nullness.NON_NULL) {
            return Nullness.NON_NULL
        }
        if (left == Nullness.NULL && right == Nullness.NULL) {
            return Nullness.NULL
        }
        if (left == Nullness.UNKNOWN && right == Nullness.UNKNOWN) {
            return Nullness.UNKNOWN
        }
        if (left == Nullness.UNKNOWN && right == Nullness.NON_NULL) {
            return Nullness.UNKNOWN
        }
        if (left == Nullness.NON_NULL && right == Nullness.UNKNOWN) {
            return Nullness.UNKNOWN
        }
        return Nullness.MAYBE_NULL
    }

    private static <T> Set<T> intersection(Set<T> left, Set<T> right) {
        Set<T> result = new LinkedHashSet<T>(left)
        result.retainAll(right)
        return result
    }

}
