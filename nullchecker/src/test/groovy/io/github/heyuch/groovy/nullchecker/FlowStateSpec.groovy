package io.github.heyuch.groovy.nullchecker

import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.VariableExpression
import spock.lang.Specification

/** Phase 0: may facts union, must facts intersect, unreachable branches are excluded by the caller. */
class FlowStateSpec extends Specification {

    def 'nullness joins by union and must facts join by intersection'() {
        given:
        Variable a = new VariableExpression('a')
        Variable b = new VariableExpression('b')
        Variable c = new VariableExpression('c')
        FlowState left = new FlowState([a] as Set, [a, b] as Set, [a] as Set, ['map|left'] as Set)
        FlowState right = new FlowState([c] as Set, [b] as Set, [] as Set, ['map|right'] as Set)

        when:
        FlowState joined = FlowState.join(left, right)

        then:
        joined.nullableVars == [a, c] as Set
        joined.guardedVars == [b] as Set
        joined.nonEmptyVars.isEmpty()
        joined.lookupGuards.isEmpty()
    }

    def 'joining with an unreachable side returns a copy of the other side'() {
        given:
        Variable a = new VariableExpression('a')
        FlowState state = new FlowState([a] as Set, [a] as Set, [a] as Set, ['map|key'] as Set)

        when:
        FlowState joined = FlowState.join(null, state)

        then:
        joined != null
        joined.nullableVars == [a] as Set
        joined.guardedVars == [a] as Set
        joined.nonEmptyVars == [a] as Set
        joined.lookupGuards == ['map|key'] as Set
    }

    def 'join and copy do not alias the source sets'() {
        given:
        Variable a = new VariableExpression('a')
        FlowState state = new FlowState([a] as Set, [a] as Set, [a] as Set, ['map|key'] as Set)

        when:
        FlowState copy = state.copy()
        copy.nullableVars.clear()
        copy.guardedVars.clear()

        then:
        state.nullableVars == [a] as Set
        state.guardedVars == [a] as Set
    }
}
