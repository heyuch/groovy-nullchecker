package corpus.pkg.dep

import groovy.transform.TypeChecked

/** Guard N11: no marking anywhere in scope, so unannotated code stays silent. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class UnmarkedPackageClass {
    String returnsNull() {
        return null
    }
}
