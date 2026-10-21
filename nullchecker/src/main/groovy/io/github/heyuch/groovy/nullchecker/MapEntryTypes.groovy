package io.github.heyuch.groovy.nullchecker

import groovy.transform.PackageScope
import org.codehaus.groovy.ast.ClassNode

/** Key and value types of a map being iterated. */
@PackageScope
class MapEntryTypes {
    ClassNode keyType
    ClassNode valueType
}
