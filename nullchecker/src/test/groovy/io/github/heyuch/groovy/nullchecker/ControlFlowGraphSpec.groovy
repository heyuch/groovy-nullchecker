package io.github.heyuch.groovy.nullchecker

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.codehaus.groovy.control.CompilePhase
import spock.lang.Specification

/** F2b: the structural CFG has the edges the dataflow will consume. */
class ControlFlowGraphSpec extends Specification {

    static final String SOURCE = '''
class Sample {
    void iff(boolean c) {
        if (c) {
            println 'x'
        } else {
            println 'y'
        }
    }

    void loop(boolean c) {
        while (c) {
            if (c) {
                break
            }
        }
    }

    void forLoop(List<String> values) {
        for (String value : values) {
            println value
        }
    }

    void doLoop(boolean c) {
        do {
            println 'x'
        } while (c)
    }

    void sw(int value) {
        switch (value) {
            case 1:
                break
            default:
                println 'd'
        }
    }

    void throwsOutside() {
        throw new RuntimeException('outside')
    }

    void throwsInside() {
        try {
            throw new RuntimeException('inside')
        } catch (RuntimeException e) {
            println e.message
        }
    }

    void throwsNoCatch() {
        try {
            throw new RuntimeException('inside')
        } finally {
            println 'done'
        }
    }
}
'''

    def 'if has true and false edges'() {
        when:
        ControlFlowGraph graph = graphFor('iff')
        CfgNode node = graph.nodes.find { CfgNode current -> current.statement instanceof IfStatement }

        then:
        node != null
        node.outgoing.collect { CfgEdge edge -> edge.kind } as Set ==
                [CfgEdgeKind.TRUE, CfgEdgeKind.FALSE] as Set
    }

    def 'while has a true edge, a false exit and a back edge'() {
        when:
        ControlFlowGraph graph = graphFor('loop')
        CfgNode loop = graph.nodes.find { CfgNode current -> current.statement instanceof WhileStatement }
        CfgNode breakNode = graph.nodes.find { CfgNode current -> current.statement instanceof BreakStatement }

        then:
        loop.outgoing.collect { CfgEdge edge -> edge.kind } as Set ==
                [CfgEdgeKind.TRUE, CfgEdgeKind.FALSE] as Set
        graph.nodes.any { CfgNode current ->
            current.outgoing.any { CfgEdge edge -> edge.target == loop }
        }
        breakNode != null
        breakNode.outgoing.size() == 1
    }

    def 'for and do-while have body edges'() {
        when:
        ControlFlowGraph forGraph = graphFor('forLoop')
        ControlFlowGraph doGraph = graphFor('doLoop')

        then:
        forGraph.nodes.any { CfgNode current ->
            current.statement instanceof ForStatement &&
                    current.outgoing.collect { CfgEdge edge -> edge.kind }.contains(CfgEdgeKind.TRUE)
        }
        doGraph.nodes.any { CfgNode current -> current.statement instanceof DoWhileStatement }
    }

    def 'switch has one edge per case and default'() {
        when:
        ControlFlowGraph graph = graphFor('sw')
        CfgNode node = graph.nodes.find { CfgNode current -> current.statement instanceof SwitchStatement }

        then:
        node != null
        node.outgoing.size() >= 2
    }

    def 'an uncaught throw goes to the exceptional exit'() {
        when:
        ControlFlowGraph graph = graphFor('throwsOutside')
        CfgNode thrown = graph.nodes.find { CfgNode current -> current.statement instanceof ThrowStatement }

        then:
        thrown != null
        graph.exceptionExit != null
        thrown.outgoing.size() == 1
        thrown.outgoing.first().kind == CfgEdgeKind.EXCEPTION
        thrown.outgoing.first().target.is(graph.exceptionExit)
    }

    def 'a throw inside try goes to the catch entry'() {
        when:
        ControlFlowGraph graph = graphFor('throwsInside')
        CfgNode thrown = graph.nodes.find { CfgNode current -> current.statement instanceof ThrowStatement }
        CfgNode caught = graph.nodes.find { CfgNode current -> current.catchStatement != null }

        then:
        thrown != null
        caught != null
        thrown.outgoing.any { CfgEdge edge ->
            edge.kind == CfgEdgeKind.EXCEPTION && edge.target.is(caught)
        }
    }

    def 'a throw in try without catch goes through finally'() {
        when:
        ControlFlowGraph graph = graphFor('throwsNoCatch')
        CfgNode thrown = graph.nodes.find { CfgNode current -> current.statement instanceof ThrowStatement }
        CfgNode tryNode = graph.nodes.find { CfgNode current -> current.statement instanceof TryCatchStatement }

        then:
        thrown != null
        tryNode != null
        thrown.outgoing.every { CfgEdge edge -> edge.kind == CfgEdgeKind.EXCEPTION }
        thrown.outgoing.first().target != graph.exit
    }

    private static ControlFlowGraph graphFor(String methodName) {
        List<Object> nodes = new AstBuilder().buildFromString(CompilePhase.CONVERSION, false, SOURCE)
        ClassNode clazz = (ClassNode) nodes.find { Object node -> node instanceof ClassNode }
        MethodNode method = clazz.methods.find { MethodNode candidate -> candidate.name == methodName }
        return ControlFlowGraph.build(method.code)
    }
}
