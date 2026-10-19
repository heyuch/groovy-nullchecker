package corpus.pkg.params

import groovy.transform.TypeChecked

/**
 * Rule R07: a package-level parameter default makes every parameter non-null.
 *
 * The annotation constrains parameters only; it is a switch on the default, not a
 * statement about any particular value.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ParamsDefault {
    static void take(String value) {
    }

    static void call() {
        take(null)
    }
}
