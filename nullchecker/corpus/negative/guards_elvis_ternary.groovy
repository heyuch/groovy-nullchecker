package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: elvis `?:` and a null-test ternary both yield a non-null value. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsElvisTernary {
    int elvis(@Nullable String s) {
        String t = s ?: 'default'
        return t.length()
    }

    int ternary(@Nullable String s) {
        String t = s == null ? 'default' : s
        return t.length()
    }
}
