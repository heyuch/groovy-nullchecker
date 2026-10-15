package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Rule R06: dereferencing an @Nullable parameter with no guard. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NullableDerefParam {
    int size(@Nullable String s) {
        return s.length()
    }
}
