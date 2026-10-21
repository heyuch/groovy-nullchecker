package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** Transfers the state through one CFG node. */
@CompileStatic
@PackageScope
abstract class FlowTransfer {

    abstract FlowState transfer(CfgNode node, FlowState input)

}
