package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** One {@code args -> result} clause of a {@code @Contract}. */
@CompileStatic
@PackageScope
class ContractClause {
    List<String> arguments = new ArrayList<String>()
    String result
}
