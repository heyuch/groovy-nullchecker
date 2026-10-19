package corpus.pkg.spring

import groovy.transform.TypeChecked

/**
 * Rule R01 and R07: Spring's {@code @NonNullApi} covers parameters and return values.
 *
 * It is the package-level marking a Spring codebase reaches for first, and it is neither
 * {@code @NullMarked} (which also covers fields) nor the JSR-305 single-scope annotations.
 */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class SpringDefault {
    static String render() {
        return null
    }

    static void take(String value) {
    }

    static void call() {
        take(null)
    }
}
