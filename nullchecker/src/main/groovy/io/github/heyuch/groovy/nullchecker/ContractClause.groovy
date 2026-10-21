package io.github.heyuch.groovy.nullchecker

import groovy.transform.PackageScope

/** One {@code args -> result} clause of a {@code @Contract}. */
@PackageScope
class ContractClause {
    List<String> arguments = new ArrayList<String>()
    String result
}
