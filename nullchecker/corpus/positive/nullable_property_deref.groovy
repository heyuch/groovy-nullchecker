package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Rule R11: dereferencing a property whose getter is @Nullable. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NullablePropertyDeref {
    @Nullable
    String getLabel() {
        return null
    }

    int run() {
        return this.label.length()
    }
}
