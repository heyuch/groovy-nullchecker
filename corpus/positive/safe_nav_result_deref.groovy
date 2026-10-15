package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Rule R08: dereferencing a safe-navigation result without a further guard. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class SafeNavResultDeref {
    int size(@Nullable String s) {
        return s?.length().intValue()
    }
}
