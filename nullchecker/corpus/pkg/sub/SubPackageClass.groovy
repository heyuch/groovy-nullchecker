package corpus.pkg.sub

import groovy.transform.TypeChecked

/**
 * Guard N11: parent package corpus.pkg.marked is @NullMarked, but JSpecify scope is the
 * annotated element itself, so this subpackage is unaffected and must stay silent.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class SubPackageClass {
    String returnsNull() {
        return null
    }
}
