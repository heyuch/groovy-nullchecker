package corpus.pkg.marked

import groovy.transform.TypeChecked

/**
 * Rule R12: the package is @NullMarked, so this class is non-null-by-default without
 * carrying any annotation of its own. Declaring the default once per package instead of
 * once per class is the whole point of the feature.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class MarkedByPackage {
    String returnsNull() {
        return null
    }
}
