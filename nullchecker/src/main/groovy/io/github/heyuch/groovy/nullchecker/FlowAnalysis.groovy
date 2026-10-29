package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.jspecify.annotations.Nullable

/**
 * Forward worklist over a CFG. The transfer is supplied by the caller; this class owns the
 * join-at-predecessors and fixed-point loop.
 */
@CompileStatic
@PackageScope
class FlowAnalysis {

    static final int DEFAULT_MAX_STEPS = 10000

    private final ControlFlowGraph graph

    private final FlowTransfer transfer

    private final FlowState initial

    private final int maxSteps

    private boolean budgetExceeded

    private final Map<CfgNode, FlowState> inStates = new LinkedHashMap<CfgNode, FlowState>()

    private final Map<CfgNode, FlowState> outStates = new LinkedHashMap<CfgNode, FlowState>()

    FlowAnalysis(ControlFlowGraph graph, FlowTransfer transfer, FlowState initial) {
        this(graph, transfer, initial, DEFAULT_MAX_STEPS)
    }

    FlowAnalysis(ControlFlowGraph graph, FlowTransfer transfer, FlowState initial, int maxSteps) {
        this.graph = graph
        this.transfer = transfer
        this.initial = initial
        this.maxSteps = maxSteps
    }

    void run() {
        Deque<CfgNode> work = new ArrayDeque<CfgNode>()
        Set<CfgNode> queued = new LinkedHashSet<CfgNode>()
        inStates.put(graph.entry, initial)
        work.addLast(graph.entry)
        queued.add(graph.entry)
        int steps = 0
        while (!work.isEmpty() && steps < maxSteps) {
            CfgNode node = work.removeFirst()
            queued.remove(node)
            FlowState input = inStates.get(node)
            if (input == null) {
                continue
            }
            FlowState output = transfer.transfer(node, input)
            if (output == null) {
                outStates.remove(node)
                continue
            }
            FlowState previous = outStates.put(node, output)
            if (previous?.sameAs(output)) {
                continue
            }
            for (CfgEdge edge : node.outgoing) {
                FlowState refined = transfer.refine(edge, output)
                propagate(edge.target, refined, work, queued)
            }
            steps++
        }
        budgetExceeded = !work.isEmpty() && steps >= maxSteps
    }

    private void propagate(CfgNode target, FlowState state, Deque<CfgNode> work, Set<CfgNode> queued) {
        FlowState existing = inStates.get(target)
        FlowState merged = FlowState.join(existing, state)
        if (merged == null || merged.sameAs(existing)) {
            return
        }
        inStates.put(target, merged)
        if (!queued.contains(target)) {
            work.addLast(target)
            queued.add(target)
        }
    }

    boolean isBudgetExceeded() {
        return budgetExceeded
    }

    @Nullable FlowState inState(CfgNode node) {
        return inStates.get(node)
    }

    @Nullable FlowState exitState() {
        return inStates.get(graph.exit)
    }

    @Nullable FlowState outState(CfgNode node) {
        return outStates.get(node)
    }

}
