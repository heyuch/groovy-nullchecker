package corpus.positive

import corpus.support.NonNull
import groovy.transform.TypeChecked

/** Rule R02: returning null from an @NonNull method. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullReturn {
    @NonNull
    String mustNotReturnNull() {
        return null
    }
}
