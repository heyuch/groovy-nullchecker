package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** A directed CFG edge. */
@CompileStatic
@PackageScope
class CfgEdge {

    final CfgNode source

    final CfgNode target

    final CfgEdgeKind kind

    CfgEdge(CfgNode source, CfgNode target, CfgEdgeKind kind) {
        this.source = source
        this.target = target
        this.kind = kind
    }

}
