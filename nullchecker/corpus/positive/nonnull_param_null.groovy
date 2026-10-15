package corpus.positive

import corpus.support.NonNull
import groovy.transform.TypeChecked

/** Rule R12: passing null to an @NonNull parameter. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullParamNull {
    void take(@NonNull String s) {
        s.length()
    }

    void caller() {
        take(null)
    }
}
