package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.ClassNode

/** Key and value types of a map being iterated. */
@CompileStatic
@PackageScope
class MapEntryTypes {
    ClassNode keyType
    ClassNode valueType
}
