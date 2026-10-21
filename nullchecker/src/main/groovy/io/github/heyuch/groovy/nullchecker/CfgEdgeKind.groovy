package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** How control reaches the next CFG node. */
@CompileStatic
@PackageScope
enum CfgEdgeKind {

    NORMAL,

    TRUE,

    FALSE,

    EXCEPTION

}
