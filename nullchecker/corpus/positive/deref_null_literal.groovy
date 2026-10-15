package corpus.positive

import groovy.transform.TypeChecked

/** Rule R09: dereferencing a literal null. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class DerefNullLiteral {
    String run() {
        return null.toString()
    }
}
