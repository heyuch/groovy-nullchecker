package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** The nullness lattice used by the strict flow state. */
@CompileStatic
@PackageScope
enum Nullness {

    NON_NULL,

    NULL,

    MAYBE_NULL,

    UNKNOWN

}
