package io.github.heyuch.groovy.nullchecker

import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.VariableExpression
import spock.lang.Specification

/** F1: per-variable nullness, unknown-suppressing join and must-fact intersection. */
class FlowStateSpec extends Specification {

    def 'locals join by the nullness table and must facts intersect'() {
        given:
        Variable a = new VariableExpression('a')
        Variable b = new VariableExpression('b')
        Variable c = new VariableExpression('c')
        FlowState left = new FlowState(
                [(a): Nullness.MAYBE_NULL, (b): Nullness.NON_NULL], [:],
                [a] as Set, ['map|left'] as Set)
        FlowState right = new FlowState(
                [(c): Nullness.MAYBE_NULL, (b): Nullness.NON_NULL], [:],
                [] as Set, ['map|right'] as Set)

        when:
        FlowState joined = FlowState.join(left, right)

        then:
        joined.locals[a] == Nullness.MAYBE_NULL
        joined.locals[b] == Nullness.NON_NULL
        joined.locals[c] == Nullness.MAYBE_NULL
        joined.nonEmptyVars.isEmpty()
        joined.lookupGuards.isEmpty()
    }

    def 'unknown plus null becomes maybe-null; unknown plus non-null stays unknown'() {
        expect:
        FlowState.joinValue(left, right) == expected

        where:
        left                  | right                 || expected
        Nullness.NULL         | Nullness.UNKNOWN      || Nullness.MAYBE_NULL
        Nullness.UNKNOWN      | Nullness.NULL         || Nullness.MAYBE_NULL
        Nullness.NON_NULL     | Nullness.UNKNOWN      || Nullness.UNKNOWN
        Nullness.UNKNOWN      | Nullness.NON_NULL     || Nullness.UNKNOWN
        Nullness.NULL         | Nullness.NON_NULL     || Nullness.MAYBE_NULL
        Nullness.MAYBE_NULL   | Nullness.NON_NULL     || Nullness.MAYBE_NULL
        Nullness.UNKNOWN      | Nullness.UNKNOWN      || Nullness.UNKNOWN
    }

    def 'joining with an unreachable side returns a copy of the other side'() {
        given:
        Variable a = new VariableExpression('a')
        FlowState state = new FlowState(
                [(a): Nullness.NON_NULL], [:], [a] as Set, ['map|key'] as Set)

        when:
        FlowState joined = FlowState.join(null, state)

        then:
        joined != null
        joined.locals[a] == Nullness.NON_NULL
        joined.nonEmptyVars == [a] as Set
        joined.lookupGuards == ['map|key'] as Set
    }

    def 'sameAs compares locals and must facts'() {
        given:
        Variable a = new VariableExpression('a')
        FlowState state = new FlowState([(a): Nullness.NON_NULL], [:], [a] as Set, ['map|key'] as Set)

        expect:
        state.sameAs(state.copy())
        !state.sameAs(null)
    }

    def 'join and copy do not alias the source state'() {
        given:
        Variable a = new VariableExpression('a')
        FlowState state = new FlowState(
                [(a): Nullness.MAYBE_NULL], [:], [a] as Set, ['map|key'] as Set)

        when:
        FlowState copy = state.copy()
        copy.locals.clear()
        copy.nonEmptyVars.clear()

        then:
        state.locals[a] == Nullness.MAYBE_NULL
        state.nonEmptyVars == [a] as Set
    }
}
