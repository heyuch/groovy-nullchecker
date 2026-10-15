package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: early `return` and early `throw` after a null test. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsEarlyExit {
    int earlyReturn(@Nullable String s) {
        if (s == null) {
            return 0
        }
        return s.length()
    }

    int earlyThrow(@Nullable String s) {
        if (s == null) {
            throw new IllegalArgumentException('null')
        }
        return s.length()
    }
}
