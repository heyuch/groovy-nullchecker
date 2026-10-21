package corpus.strict

import groovy.transform.TypeChecked

/** F3: try/catch/finally edges carry state to the handler and the finally block. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class FlowTryCatch {

    int maybeNull(boolean c) {
        String x = 'a'
        try {
            if (c) {
                x = null
            }
        } catch (RuntimeException e) {
            x = 'b'
        }
        return x.length()
    }

    int finallyWins() {
        String x = null
        try {
            x = null
        } finally {
            x = 'c'
        }
        return x.length()
    }

    int catchWins() {
        String x = null
        try {
            throw new RuntimeException('x')
        } catch (RuntimeException e) {
            x = 'b'
        }
        return x.length()
    }
}
