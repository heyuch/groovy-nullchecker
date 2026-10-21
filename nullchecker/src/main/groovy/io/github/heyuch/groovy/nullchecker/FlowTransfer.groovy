package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.jspecify.annotations.Nullable

/** Transfers the state through one CFG node. */
@CompileStatic
@PackageScope
abstract class FlowTransfer {

    abstract @Nullable FlowState transfer(CfgNode node, FlowState input)

    /** Refines the state on one outgoing edge. */
    abstract FlowState refine(CfgEdge edge, FlowState state)

}
