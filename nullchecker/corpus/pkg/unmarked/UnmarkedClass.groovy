package corpus.pkg.unmarked

import corpus.support.NullUnmarked
import groovy.transform.TypeChecked

/** Guard N10: an explicit @NullUnmarked wins over the package-level default. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
@NullUnmarked
class UnmarkedClass {
    String returnsNull() {
        return null
    }
}
