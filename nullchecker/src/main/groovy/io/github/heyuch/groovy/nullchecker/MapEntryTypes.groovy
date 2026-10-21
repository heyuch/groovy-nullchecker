package io.github.heyuch.groovy.nullchecker

import org.jspecify.annotations.Nullable

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.ClassNode

/** Key and value types of a map being iterated. */
@CompileStatic
@PackageScope
class MapEntryTypes {

    @Nullable ClassNode keyType
    @Nullable ClassNode valueType

}
