package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: `&&` and `||` short-circuit forms. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsShortCircuit {
    int andForm(@Nullable String s) {
        if (s != null && s.length() > 0) {
            return s.length()
        }
        return 0
    }

    int orForm(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return 0
        }
        return s.length()
    }
}
