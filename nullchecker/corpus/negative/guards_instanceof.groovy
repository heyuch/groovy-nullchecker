package corpus.negative

import groovy.transform.TypeChecked

/** Guards: `instanceof` narrows, and `!instanceof` with an early exit. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class GuardsInstanceof {
    int instanceofGuard(Object o) {
        if (o instanceof String) {
            return o.length()
        }
        return 0
    }

    int notInstanceofReturns(Object o) {
        if (!(o instanceof String)) {
            return 0
        }
        return o.length()
    }
}
