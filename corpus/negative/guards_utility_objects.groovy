package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: Objects.nonNull / Objects.isNull matched by simple name. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsUtilityObjects {
    int nonNull(@Nullable String s) {
        if (Objects.nonNull(s)) {
            return s.length()
        }
        return 0
    }

    int isNullReturns(@Nullable String s) {
        if (Objects.isNull(s)) {
            return 0
        }
        return s.length()
    }
}
