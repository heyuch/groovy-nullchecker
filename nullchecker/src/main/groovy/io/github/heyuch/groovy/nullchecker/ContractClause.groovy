package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.jspecify.annotations.Nullable

/** One {@code args -> result} clause of a {@code @Contract}. */
@CompileStatic
@PackageScope
class ContractClause {

    List<String> arguments = new ArrayList<String>()
    @Nullable String result

}
