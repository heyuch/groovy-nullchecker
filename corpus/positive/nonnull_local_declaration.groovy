package corpus.positive

import corpus.support.NonNull
import groovy.transform.TypeChecked

/** Rule R05: declaring an @NonNull local with a null initialiser. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullLocalDeclaration {
    void run() {
        @NonNull String s = null
        s.length()
    }
}
