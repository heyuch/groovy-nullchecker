package io.github.heyuch.groovy.nullchecker

import org.jspecify.annotations.Nullable

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** What a call hands to its closure: an element, a map entry, or a pair of key and value. */
@CompileStatic
@PackageScope
class ClosureHint {

    boolean elementNullable
    /** True for each/eachWithIndex, which pass (key, value) rather than the entry. */
    boolean pairwise
    @Nullable MapEntryTypes entryTypes

}
