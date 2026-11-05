package io.github.heyuch.groovy.nullchecker

import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Small, deterministic FlowState microbenchmark. Not a test: it is run explicitly from the
 * test classpath so a normalized regression can be recorded in docs/PERF.md.
 */
class FlowStateBench {

    private static final int VARIABLES = 100

    private static final int PATHS = 100

    private static final int WARMUP = 2000

    private static final int ITERATIONS = 10000

    static void main(String[] args) {
        List<Variable> variables = []
        Map<Variable, Nullness> locals = [:]
        Map<String, Nullness> paths = [:]
        for (int i = 0; i < VARIABLES; i++) {
            Variable variable = new VariableExpression("v${i}")
            variables.add(variable)
            locals.put(variable, Nullness.MAYBE_NULL)
        }
        for (int i = 0; i < PATHS; i++) {
            paths.put("v${i}.field", Nullness.NON_NULL)
        }
        FlowState left = new FlowState(locals, paths, [] as Set, [] as Set)
        FlowState right = new FlowState(locals, paths, [] as Set, [] as Set)

        run('warmup', left, right, WARMUP)
        run('join', left, right, ITERATIONS)
        runCopy('copy/restore', left, ITERATIONS)
    }

    static void run(String label, FlowState left, FlowState right, int iterations) {
        long start = System.nanoTime()
        for (int i = 0; i < iterations; i++) {
            FlowState.join(left, right)
        }
        report(label, System.nanoTime() - start, iterations)
    }

    static void runCopy(String label, FlowState state, int iterations) {
        long start = System.nanoTime()
        for (int i = 0; i < iterations; i++) {
            state.copy()
        }
        report(label, System.nanoTime() - start, iterations)
    }

    static void report(String label, long nanos, int iterations) {
        double micros = nanos / 1000.0d / iterations
        println String.format('%s: %.3f us/op (%d iterations)', label, micros, iterations)
    }
}
