package corpus.strict

import groovy.transform.TypeChecked

/**
 * Rule R10: flow-sensitive deref of a variable known to be null.
 * Requires strict mode -- nothing here is annotated.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowLocalNullDeref {
    int run() {
        String s = null
        return s.length()
    }
}
