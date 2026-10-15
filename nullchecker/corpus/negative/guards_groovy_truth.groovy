package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: Groovy-truth `if (x)` and its negation with an early exit. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsGroovyTruth {
    int ifTruthy(@Nullable String s) {
        if (s) {
            return s.length()
        }
        return 0
    }

    int ifNotTruthyReturns(@Nullable String s) {
        if (!s) {
            return 0
        }
        return s.length()
    }
}
