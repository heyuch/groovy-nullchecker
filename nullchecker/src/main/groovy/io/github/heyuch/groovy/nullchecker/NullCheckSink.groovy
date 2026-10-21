package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** Destination for findings when they must not become compiler errors. */
@CompileStatic
@PackageScope
interface NullCheckSink {

    void report(NullCheckFinding finding)
}
