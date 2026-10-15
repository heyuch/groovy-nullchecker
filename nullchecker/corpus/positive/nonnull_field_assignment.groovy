package corpus.positive

import corpus.support.NonNull
import groovy.transform.TypeChecked

/** Rule R04: assigning null to an @NonNull field. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullFieldAssignment {
    @NonNull
    String name = 'ok'

    void wipe() {
        name = null
    }
}
