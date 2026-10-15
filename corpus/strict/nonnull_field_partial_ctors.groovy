package corpus.strict

import corpus.support.NonNull
import groovy.transform.TypeChecked

/**
 * Rule R14 (alias): @NonNull field is initialised by some constructors but not all.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullFieldPartialCtors {
    @NonNull
    String name

    NonNullFieldPartialCtors() {
        name = 'set'
    }

    NonNullFieldPartialCtors(String v) {
    }
}
