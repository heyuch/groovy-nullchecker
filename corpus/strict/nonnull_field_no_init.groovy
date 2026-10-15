package corpus.strict

import corpus.support.NonNull
import groovy.transform.TypeChecked

/**
 * Rule R14: an explicitly @NonNull instance field is never initialised.
 * Requires strict mode, where definite-initialisation checking runs.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class NonNullFieldNoInit {
    @NonNull
    String neverSet
}
