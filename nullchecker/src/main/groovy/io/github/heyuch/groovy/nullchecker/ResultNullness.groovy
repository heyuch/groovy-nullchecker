package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** How a call's result relates to nullness. */
@CompileStatic
@PackageScope
enum ResultNullness {
    /** Never null by this contract. */
    NON_NULL,
    /** Null when the operation has no result. */
    NULLABLE,
    /** The value of the given argument. */
    PARAM,
    /** Nullable exactly when the given argument is nullable. */
    NULLABLE_IF_ARG_NULLABLE
}
