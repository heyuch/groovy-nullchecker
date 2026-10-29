package io.github.heyuch.groovy.nullchecker

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.CompilePhase
import spock.lang.Specification

/** F2b: the worklist joins predecessor states and reaches the method exit. */
class FlowAnalysisSpec extends Specification {

    static final String SOURCE = '''
class Sample {
    void pick(boolean c) {
        if (c) {
            a()
        } else {
            b()
        }
    }

    void a() {}
    void b() {}
}
'''

    def 'worklist joins a non-null branch with a null branch'() {
        given:
        ControlFlowGraph graph = graphFor('pick')
        Variable marker = new VariableExpression('marker')
        FlowTransfer transfer = new MarkerTransfer(marker)

        when:
        FlowAnalysis analysis = new FlowAnalysis(graph, transfer,
                new FlowState([:], [:], [] as Set, [] as Set))
        analysis.run()

        then:
        analysis.inState(graph.exit) != null
        analysis.inState(graph.exit).locals[marker] == Nullness.MAYBE_NULL
    }

    def 'worklist exposes budget exhaustion instead of silently stopping'() {
        given:
        ControlFlowGraph graph = graphFor('pick')
        Variable marker = new VariableExpression('marker')
        FlowTransfer transfer = new MarkerTransfer(marker)

        when:
        FlowAnalysis analysis = new FlowAnalysis(graph, transfer,
                new FlowState([:], [:], [] as Set, [] as Set), 1)
        analysis.run()

        then:
        analysis.budgetExceeded
    }

    private static ControlFlowGraph graphFor(String methodName) {
        List<Object> nodes = new AstBuilder().buildFromString(CompilePhase.CONVERSION, false, SOURCE)
        ClassNode clazz = (ClassNode) nodes.find { Object node -> node instanceof ClassNode }
        MethodNode method = clazz.methods.find { MethodNode candidate -> candidate.name == methodName }
        return ControlFlowGraph.build(method.code)
    }

    private static class MarkerTransfer extends FlowTransfer {

        private final Variable marker

        MarkerTransfer(Variable marker) {
            this.marker = marker
        }

        @Override
        FlowState transfer(CfgNode node, FlowState input) {
            if (!(node.statement instanceof ExpressionStatement)) {
                return input
            }
            ExpressionStatement statement = (ExpressionStatement) node.statement
            if (!(statement.expression instanceof MethodCallExpression)) {
                return input
            }
            MethodCallExpression call = (MethodCallExpression) statement.expression
            Map<Variable, Nullness> locals = new LinkedHashMap<Variable, Nullness>(input.locals)
            if (call.methodAsString == 'a') {
                locals.put(marker, Nullness.NON_NULL)
            } else if (call.methodAsString == 'b') {
                locals.put(marker, Nullness.NULL)
            }
            return new FlowState(locals, input.paths, input.nonEmptyVars, input.lookupGuards)
        }

        @Override
        FlowState refine(CfgEdge edge, FlowState state) {
            return state
        }
    }
}
