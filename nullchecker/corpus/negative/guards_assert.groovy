package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: `assert x` and `assert x != null`. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsAssert {
    int assertTruthy(@Nullable String s) {
        assert s
        return s.length()
    }

    int assertNotNull(@Nullable String s) {
        assert s != null
        return s.length()
    }
}
