package corpus.negative

import corpus.support.Nullable
import corpus.support.Validators
import groovy.transform.TypeChecked

/** Guards: validator calls that throw on null (requireNonNull / checkNotNull). */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsValidators {
    int requireNonNull(@Nullable String s) {
        Validators.requireNonNull(s)
        return s.length()
    }

    int checkNotNull(@Nullable String s) {
        Validators.checkNotNull(s)
        return s.length()
    }
}
