package corpus.positive

import corpus.support.Nullable
import groovy.transform.TypeChecked

/** Without an assertion contract the condition is not presumed true after the call. */
@TypeChecked(extensions = '@NULLCHECKER_EXTENSION@')
class AssertionWithoutContract {

    static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message)
        }
    }

    @Nullable String outcome

    void run() {
        isTrue(outcome != null, 'must have a value')
        outcome.length()
    }
}
