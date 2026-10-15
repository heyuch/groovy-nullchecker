package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Rule R07: dereferencing the result of an @Nullable-returning method. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NullableReturnDeref {
    @Nullable
    String maybe() {
        return null
    }

    int size() {
        return maybe().length()
    }
}
