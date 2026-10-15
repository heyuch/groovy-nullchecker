package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: `if (x != null)`, `if (x == null)` with an else branch. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsNullComparison {
    int ifNotEqual(@Nullable String s) {
        if (s != null) {
            return s.length()
        }
        return 0
    }

    int ifEqualElse(@Nullable String s) {
        if (s == null) {
            return 0
        } else {
            return s.length()
        }
    }
}
