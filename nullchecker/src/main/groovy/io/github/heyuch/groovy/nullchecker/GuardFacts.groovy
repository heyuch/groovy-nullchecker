package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.Variable

/** The variables a condition proves non-null in each branch, and the lookups it proves present. */
@CompileStatic
@PackageScope
class GuardFacts {

    Set<Variable> whenTrue = new HashSet<Variable>()
    Set<Variable> whenFalse = new HashSet<Variable>()
    /** Lookup keys (container text|key text) proved present in that branch. */
    Set<String> lookupsTrue = new HashSet<String>()
    Set<String> lookupsFalse = new HashSet<String>()
    /** Finite field paths proved non-null in that branch. */
    Set<String> pathsTrue = new HashSet<String>()
    Set<String> pathsFalse = new HashSet<String>()
    /** Collections proved non-empty in that branch (max/min can then not return null). */
    Set<Variable> nonEmptyTrue = new HashSet<Variable>()
    Set<Variable> nonEmptyFalse = new HashSet<Variable>()

}
