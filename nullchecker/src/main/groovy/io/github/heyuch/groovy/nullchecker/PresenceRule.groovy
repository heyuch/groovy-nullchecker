package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/** A lookup that is non-null only when the same key is known to be present. */
@CompileStatic
@PackageScope
class PresenceRule {

    int argument

}
