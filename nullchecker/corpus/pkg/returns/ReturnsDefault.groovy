package corpus.pkg.returns

import groovy.transform.TypeChecked

/**
 * Rule R01: a package-level return default makes every return value non-null.
 *
 * A parameter is deliberately left able to be null: the two defaults are separate, and
 * covering one must not silently cover the other.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class ReturnsDefault {
    static String render() {
        return null
    }

    static void take(String value) {
    }

    static void call() {
        take(null)
    }
}
