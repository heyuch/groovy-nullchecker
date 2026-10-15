package corpus.negative

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Guards: a while-loop condition clears nullability inside the body. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsWhile {
    int loop(@Nullable String s) {
        int total = 0
        while (s != null) {
            total += s.length()
            break
        }
        return total
    }
}
